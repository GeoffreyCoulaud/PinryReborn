package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImportIssue
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportIssueKind
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportIssueRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinCreator
import fr.geoffreyCoulaud.pinryReborn.api.worker.ImportsConfig
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Isolated, writable data directories for the class run, as in [ModeBImageHostingTestProfile], so the
 * mode-A upload can write, the import upload can stream, and the cleaner can erase both.
 */
class MeDeleteCompletionTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> =
        mapOf(
            "images.data_dir" to "build/test-image-data/${UUID.randomUUID()}",
            "imports.data_dir" to "build/test-import-data/${UUID.randomUUID()}",
        )
}

/**
 * End-to-end coverage of the async account-deletion completeness path (spec §13): a real
 * `DELETE /api/v1/me` on an account that owns a pin with an uploaded image enqueues the deletion,
 * and the real async worker then erases everything (including the image row and on-disk bytes)
 * before hard-deleting the user as the last step of one transaction. Because the hard delete frees
 * the username only once that whole transaction has committed, a bounded poll that observes the
 * same username become registerable again is proof the full erasure ran end-to-end.
 */
@QuarkusTest
@TestProfile(MeDeleteCompletionTestProfile::class)
class MeDeleteCompletionIntegrationTest : IntegrationTest() {

    @Inject
    lateinit var pinCreator: PinCreator

    @Inject
    lateinit var userDataImportRepository: UserDataImportRepositoryInterface

    @Inject
    lateinit var userDataImportIssueRepository: UserDataImportIssueRepositoryInterface

    @Inject
    lateinit var importsConfig: ImportsConfig

    private fun stepUp(password: String) =
        "password " + Base64.getUrlEncoder().encodeToString(password.toByteArray())

    private fun fixture(name: String) = File("src/test/resources/fixtures/$name")

    private fun openImport(auth: AuthenticatedUser): UUID =
        given()
            .authenticatedAs(auth)
            .`when`().post("/api/v1/me/imports")
            .then().statusCode(202)
            .extract().jsonPath().getString("id")
            .let(UUID::fromString)

    private fun uploadChunk(auth: AuthenticatedUser, importId: UUID) {
        given()
            .authenticatedAs(auth)
            .contentType("application/octet-stream")
            .body("half an archive".toByteArray())
            .`when`().put("/api/v1/me/imports/$importId/archive?offset=0")
            .then().statusCode(200)
    }

    /** A terminal import whose promoted archive is on disk, which only the derived key names. */
    private fun seedCompletedImport(auth: AuthenticatedUser): UUID {
        val importId = UUID.randomUUID()
        val archivePath = Path.of(importsConfig.dataDir()).resolve("imports/$importId.zip")
        Files.createDirectories(archivePath.parent)
        Files.write(archivePath, "a finished archive".toByteArray())
        userDataImportRepository.save(
            UserDataImport(
                id = importId,
                userId = auth.user.id,
                state = UserDataImportState.COMPLETED,
                requestedAt = Instant.now(),
                storageKey = "imports/$importId.zip",
            ),
        )
        userDataImportIssueRepository.save(
            UserDataImportIssue(
                id = UUID.randomUUID(),
                importId = importId,
                kind = UserDataImportIssueKind.LINE_REJECTED,
                line = 1,
                subject = null,
                detail = "seeded",
            ),
        )
        return importId
    }

    private fun regularFilesUnder(root: Path): List<Path> =
        if (!Files.isDirectory(root)) emptyList()
        else Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }

    /** Bounded poll until every import row of the account is gone. */
    private fun pollUntilImportsGone(userId: UUID): Boolean {
        repeat(POLL_ATTEMPTS) {
            if (userDataImportRepository.findAllImportIdsForUser(userId).isEmpty()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    /** Bounded poll until nothing is left on disk; the bytes go after the cleaner's commit. */
    private fun pollUntilDataDirEmpty(root: Path): Boolean {
        repeat(POLL_ATTEMPTS) {
            if (regularFilesUnder(root).isEmpty()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    /** Bounded poll of `POST /api/v1/users` until re-registering [name] succeeds (username freed). */
    private fun pollUntilRegistrationSucceeds(name: String, password: String): Boolean {
        repeat(POLL_ATTEMPTS) {
            val status =
                given()
                    .contentType("application/json")
                    .body("""{"name":"$name","password":"$password"}""")
                    .post("/api/v1/users")
                    .then().extract().statusCode()
            if (status == 200) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    @Test
    fun `Given an account with a pin and an uploaded image, Then deletion erases it and frees the username`() {
        // Given: an authenticated user with a real pin carrying an uploaded image
        val password = "password123"
        val auth = createAuthenticatedUser(password = password)
        val name = auth.user.name
        val pin =
            pinCreator.createPin(
                author = auth.user,
                sourceContextUrl = "https://example.com",
                sourceMediaUrl = "https://example.com/img.png",
                description = "Account deletion completeness test pin",
                tags = emptyList(),
            )
        given()
            .authenticatedAs(auth)
            .multiPart("file", fixture("sample.png"), "image/png")
            .`when`().put("/api/v1/pins/${pin.id}/image")
            .then().statusCode(201)
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/pins/${pin.id}/image")
            .then().statusCode(200)

        // When: the account is deleted with a valid step-up
        given()
            .authenticatedAs(auth)
            .header("X-Reauthentication", stepUp(password))
            .delete("/api/v1/me")
            .then().statusCode(202)

        // Then: the worker fully erases the account, so the same username becomes registerable
        // again only once the hard delete (the last step of the cleaner's transaction) has run.
        val freedWithinBound = pollUntilRegistrationSucceeds(name, password)
        assertTrue(freedWithinBound, "the deletion worker should erase the account and free the username")
    }

    @Test
    fun `Given an account holding two imports, Then deletion erases both tables and the data directory`() {
        // Given: one import still awaiting its archive, with a real chunk under tmp/, and one completed
        // import whose promoted archive and issue row are on the other side of the lifecycle
        val password = "password123"
        val auth = createAuthenticatedUser(password = password)
        val awaitingId = openImport(auth)
        uploadChunk(auth, awaitingId)
        val completedId = seedCompletedImport(auth)
        val dataDir = Path.of(importsConfig.dataDir())
        assertEquals(2, regularFilesUnder(dataDir).size, "the chunk and the archive should both be on disk")

        // When
        given()
            .authenticatedAs(auth)
            .header("X-Reauthentication", stepUp(password))
            .delete("/api/v1/me")
            .then().statusCode(202)

        // Then
        assertTrue(pollUntilImportsGone(auth.user.id), "both import rows should be erased")
        assertEquals(0, userDataImportIssueRepository.countForImport(completedId))
        assertTrue(pollUntilDataDirEmpty(dataDir), "neither the upload nor the archive should be left")
    }

    companion object {
        private const val POLL_ATTEMPTS = 50
        private const val POLL_INTERVAL_MS = 200L
    }
}
