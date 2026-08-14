package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.ReapOrphanedStorage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.ReapAbandonedUserDataImports
import fr.geoffreyCoulaud.pinryReborn.api.worker.ImportsConfig
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The import sweep against real bytes on disk (spec §6), driven through the injected beans rather than
 * the fixed-delay scheduler, as [MeExportCompletionIntegrationTest] drives the export purge.
 */
@QuarkusTest
@TestProfile(MeImportTestProfile::class)
// The app under test runs the real SystemClock; these read the wall clock to backdate fixtures against it.
@Suppress("WallClockRead")
class MeImportSweepIntegrationTest : IntegrationTest() {
    @Inject
    lateinit var repository: UserDataImportRepositoryInterface

    @Inject
    lateinit var reapAbandonedUserDataImports: ReapAbandonedUserDataImports

    @Inject
    lateinit var reapOrphanedStorage: ReapOrphanedStorage

    @Inject
    lateinit var importsConfig: ImportsConfig

    private fun dataDir(): Path = Path.of(importsConfig.dataDir())

    private fun uploadPathFor(importId: UUID): Path = dataDir().resolve("tmp/import-$importId.part")

    private fun archivePathFor(importId: UUID): Path = dataDir().resolve("imports/$importId.zip")

    private fun openImport(auth: AuthenticatedUser): UUID =
        given()
            .authenticatedAs(auth)
            .`when`().post("/api/v1/me/imports")
            .then().statusCode(202)
            .extract().jsonPath().getString("id")
            .let(UUID::fromString)

    private fun uploadChunk(auth: AuthenticatedUser, importId: UUID, bytes: ByteArray) {
        given()
            .authenticatedAs(auth)
            .contentType("application/octet-stream")
            .body(bytes)
            .`when`().put("/api/v1/me/imports/$importId/archive?offset=0")
            .then().statusCode(200)
    }

    /** Backdates the row's last activity past the grace, which is what makes the sweep select it. */
    private fun backdatePastGrace(importId: UUID) {
        val stored = requireNotNull(repository.findById(importId))
        val stale = Instant.now().minus(importsConfig.uploadGrace()).minus(Duration.ofHours(1))
        repository.save(stored.copy(lastUploadActivityAt = stale))
    }

    private fun writeArchive(importId: UUID, bytes: ByteArray): Path {
        val path = archivePathFor(importId)
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
        return path
    }

    @Test
    fun `Given an upload nobody fed for longer than the grace, Then the row and its bytes both go`() {
        // Given: a real chunk on disk under the real data directory
        val auth = createAuthenticatedUser()
        val importId = openImport(auth)
        uploadChunk(auth, importId, "half an archive".toByteArray())
        val uploadPath = uploadPathFor(importId)
        assertTrue(Files.exists(uploadPath), "the chunk should be on disk under the import's tmp path")
        backdatePastGrace(importId)

        // When
        reapAbandonedUserDataImports.reap()

        // Then
        val reaped = requireNotNull(repository.findById(importId))
        assertEquals(UserDataImportState.ABANDONED, reaped.state)
        assertFalse(Files.exists(uploadPath), "the partial upload should be unlinked once abandoned")
    }

    @Test
    fun `Given a cancelled import still holding an archive, Then the sweep reclaims the bytes once`() {
        // Given: the promoted archive of an import whose row went terminal without releasing it
        val auth = createAuthenticatedUser()
        val importId = openImport(auth)
        val stored = requireNotNull(repository.findById(importId))
        val archivePath = writeArchive(importId, "a promoted archive".toByteArray())
        repository.save(
            stored.copy(
                state = UserDataImportState.CANCELLED,
                storageKey = "imports/$importId.zip",
            ),
        )

        // When
        reapAbandonedUserDataImports.reap()

        // Then: the row stops naming bytes that are gone, so the next hour reclaims nothing
        assertFalse(Files.exists(archivePath), "the archive should be reclaimed once the row is terminal")
        assertNull(requireNotNull(repository.findById(importId)).storageKey)
    }

    @Test
    fun `Given an archive on disk with no import row at all, Then the orphan sweep reclaims it`() {
        // Given: what a completer that died between its promote and its row write leaves behind, which
        // no row-driven path can name (ADR 0003 makes this sweep the guarantor)
        val orphanId = UUID.randomUUID()
        val archivePath = writeArchive(orphanId, "an orphaned archive".toByteArray())

        // When
        reapOrphanedStorage.reap()

        // Then
        assertFalse(Files.exists(archivePath), "an archive no row names should be reclaimed")
    }

    @Test
    fun `Given an upload still in flight, Then the orphan sweep leaves it alone`() {
        // Given: uploads live under tmp/, which the storage sweep must not walk, or it would delete a
        // chunk out from under a client between two requests
        val auth = createAuthenticatedUser()
        val importId = openImport(auth)
        uploadChunk(auth, importId, "half an archive".toByteArray())
        val uploadPath = uploadPathFor(importId)

        // When
        reapOrphanedStorage.reap()

        // Then
        assertTrue(Files.exists(uploadPath), "an upload in flight is not orphaned storage")
    }
}
