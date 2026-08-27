package fr.geoffreyCoulaud.pinryReborn.api.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.ReapExpiredUserDataExports
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.EnqueueTask
import fr.geoffreyCoulaud.pinryReborn.api.worker.ExportRetentionLifecycle
import fr.geoffreyCoulaud.pinryReborn.api.worker.ExportsConfig
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.zip.ZipFile

/**
 * End-to-end coverage of the async export completion path (spec `docs/specs/2026-07-22-user-data-export.md`
 * §3, §4, §8, §9, §10), with a REAL async worker building a REAL archive on disk -- this is what
 * would have caught `Pin.image` being always null under mocked repositories, per the spec's own
 * testing-strategy rationale (§13.1).
 *
 * The archive-content test, "Given a seeded account with recycled content and a real image, Then
 * the archive is correct", is the point of the whole task: it seeds real pins, a real recycled
 * board membership and a real uploaded image, waits for the real worker, downloads the real bytes
 * and opens them as a [ZipFile].
 */
@QuarkusTest
@TestProfile(MeExportTestProfile::class)
// The app under test runs the real SystemClock; these read the wall clock to keep fixture instants consistent with it.
@Suppress("WallClockRead")
class MeExportCompletionIntegrationTest : IntegrationTest() {

    @Inject
    lateinit var pinCreator: PinCreator

    @Inject
    lateinit var boardCreator: BoardCreator

    @Inject
    lateinit var userDataExportRepository: UserDataExportRepositoryInterface

    @Inject
    lateinit var reapExpiredUserDataExports: ReapExpiredUserDataExports

    @Inject
    lateinit var exportsConfig: ExportsConfig

    @Inject
    lateinit var exportRetentionLifecycle: ExportRetentionLifecycle

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var enqueueTask: EnqueueTask

    @Inject
    lateinit var taskQueue: TaskQueueInterface

    private fun stepUp(password: String) =
        "password " + Base64.getUrlEncoder().encodeToString(password.toByteArray())

    private fun fixture(name: String) = File("src/test/resources/fixtures/$name")

    private fun archivePathFor(exportId: UUID): Path =
        Path.of(exportsConfig.dataDir()).resolve("exports/$exportId.zip")

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return HexFormat.of().formatHex(digest)
    }

    // --- Real request/poll/download plumbing (mirrors MeDeleteCompletionIntegrationTest) ---

    private fun requestExport(auth: IntegrationTest.AuthenticatedUser, password: String): UUID =
        given()
            .authenticatedAs(auth)
            .header("X-Reauthentication", stepUp(password))
            .`when`().post("/api/v1/me/exports")
            .then().statusCode(202)
            .extract().jsonPath().getString("id")
            .let(UUID::fromString)

    /** Bounded poll of `GET .../{id}` until `state == "READY"`, returning the last observed state. */
    private fun pollUntilReady(auth: IntegrationTest.AuthenticatedUser, exportId: UUID): String {
        var lastState = "UNKNOWN"
        repeat(POLL_ATTEMPTS) {
            lastState = given()
                .authenticatedAs(auth)
                .`when`().get("/api/v1/me/exports/$exportId")
                .then().extract().jsonPath().getString("state")
            if (lastState == "READY") return lastState
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return lastState
    }

    /** Bounded poll until the runtime has settled [taskId], which for a handler-less kind means DEAD. */
    private fun pollUntilTaskDead(taskId: UUID): Boolean {
        repeat(POLL_ATTEMPTS) {
            if (taskQueue.findById(taskId)?.state == TaskState.DEAD) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    /** Bounded poll until [exportId]'s row is gone (proof the account-deletion worker erased it). */
    private fun pollUntilExportGone(exportId: UUID): Boolean {
        repeat(POLL_ATTEMPTS) {
            if (userDataExportRepository.findById(exportId) == null) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    /**
     * Bounded poll until [path] is gone. The bytes go after the cleaner's transaction commits
     * (`AccountDeletionCleaner.kt:67`), so the row's disappearance does not mean the file's.
     */
    private fun pollUntilFileGone(path: Path): Boolean {
        repeat(POLL_ATTEMPTS) {
            if (!Files.exists(path)) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun downloadBytes(auth: IntegrationTest.AuthenticatedUser, exportId: UUID): ByteArray =
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/me/exports/$exportId/download")
            .then().statusCode(200)
            .extract().asByteArray()

    private fun openZip(bytes: ByteArray): ZipFile {
        val tempFile = Files.createTempFile("me-export-completion-", ".zip")
        Files.write(tempFile, bytes)
        tempFile.toFile().deleteOnExit()
        return ZipFile(tempFile.toFile())
    }

    private fun readEntryBytes(zip: ZipFile, name: String): ByteArray {
        val entry = zip.getEntry(name) ?: error("missing zip entry: $name")
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    private fun readJsonLines(zip: ZipFile, name: String): List<JsonNode> =
        String(readEntryBytes(zip, name))
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { objectMapper.readTree(it) }
            .toList()

    // --- Seeding: two active pins (one tagged and boarded), one recycled pin, an active board, a
    // recycled board holding a pin, a tag, and one real uploaded image (spec §13.1) ---

    private data class SeededContent(
        val taggedPinId: UUID,
        val imagePinId: UUID,
        val recycledPinId: UUID,
        val activeBoardId: UUID,
        val recycledBoardId: UUID,
    )

    private fun createPin(auth: IntegrationTest.AuthenticatedUser, slug: String, tags: List<String> = emptyList()) =
        pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com/$slug",
            sourceMediaUrl = "https://example.com/$slug.jpg",
            description = "Pin $slug",
            tags = tags,
        )

    private fun putPinInBoard(auth: IntegrationTest.AuthenticatedUser, pinId: UUID, boardId: UUID) {
        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"boardIds": ["$boardId"]}""")
            .`when`().put("/api/v1/pins/$pinId/boards")
            .then().statusCode(200)
    }

    private fun seedArchiveContent(auth: IntegrationTest.AuthenticatedUser): SeededContent {
        val taggedPin = createPin(auth, "tagged", tags = listOf("nature"))
        val imagePin = createPin(auth, "image")
        val recycledPin = createPin(auth, "recycled")
        given()
            .authenticatedAs(auth)
            .multiPart("file", fixture("sample.png"), "image/png")
            .`when`().put("/api/v1/pins/${imagePin.id}/image")
            .then().statusCode(201)
        given().authenticatedAs(auth).`when`().delete("/api/v1/pins/${recycledPin.id}").then().statusCode(204)

        val activeBoard = boardCreator.create(author = auth.user, name = "Active board", description = "")
        val recycledBoard = boardCreator.create(author = auth.user, name = "Recycled board", description = "")
        putPinInBoard(auth, taggedPin.id, recycledBoard.id)
        given().authenticatedAs(auth).`when`().delete("/api/v1/boards/${recycledBoard.id}").then().statusCode(204)

        return SeededContent(
            taggedPinId = taggedPin.id,
            imagePinId = imagePin.id,
            recycledPinId = recycledPin.id,
            activeBoardId = activeBoard.id,
            recycledBoardId = recycledBoard.id,
        )
    }

    // --- Archive-content assertions ---

    private fun assertManifestCounts(manifest: JsonNode) {
        assertEquals(1, manifest.get("formatVersion").asInt())
        val counts = manifest.get("counts")
        assertEquals(3, counts.get("pins").asInt(), "two active pins + one recycled pin")
        assertEquals(2, counts.get("boards").asInt(), "one active board + one recycled board")
        assertEquals(1, counts.get("tags").asInt())
        assertEquals(1, counts.get("images").asInt())
    }

    private fun assertRecycledPinCarriesDeletedAt(pinLines: List<JsonNode>, recycledPinId: UUID) {
        val line = pinLines.first { it.get("id").asText() == recycledPinId.toString() }
        assertFalse(line.get("deletedAt").isNull, "the recycled pin should carry a non-null deletedAt")
    }

    /** The single most important assertion: a recycled board still appears in its pin's `boards`. */
    private fun assertRecycledBoardMembershipSurvives(pinLines: List<JsonNode>, seeded: SeededContent) {
        val line = pinLines.first { it.get("id").asText() == seeded.taggedPinId.toString() }
        val boardIds = line.get("boards").map { it.get("id").asText() }
        assertTrue(
            seeded.recycledBoardId.toString() in boardIds,
            "the recycled board should stay listed in its pin's boards even though it is recycled",
        )
        val tagNames = line.get("tags").map { it.get("name").asText() }
        assertTrue("nature" in tagNames, "the pin's tag should be exported")
    }

    private fun assertBoardsJsonl(zip: ZipFile, seeded: SeededContent) {
        val boardLines = readJsonLines(zip, "boards.jsonl")
        assertEquals(2, boardLines.size)
        val recycled = boardLines.first { it.get("id").asText() == seeded.recycledBoardId.toString() }
        assertFalse(recycled.get("deletedAt").isNull, "the recycled board should carry a non-null deletedAt")
        val active = boardLines.first { it.get("id").asText() == seeded.activeBoardId.toString() }
        assertTrue(active.get("deletedAt").isNull, "the active board should carry a null deletedAt")
    }

    /** Downloads REAL bytes and compares them byte-for-byte to the uploaded fixture. */
    private fun assertImageEntryIsByteIdentical(zip: ZipFile, pinLines: List<JsonNode>, imagePinId: UUID) {
        val line = pinLines.first { it.get("id").asText() == imagePinId.toString() }
        val imageNode = line.get("image")
        assertFalse(imageNode.isNull, "the pin with an uploaded image should carry a non-null image")
        val path = imageNode.get("path").asText()
        val actualBytes = readEntryBytes(zip, path)
        val expectedBytes = fixture("sample.png").readBytes()
        val message = "the image entry should be byte-identical to the uploaded fixture"
        assertArrayEquals(expectedBytes, actualBytes, message)
    }

    private fun assertEveryEntryDigestMatches(zip: ZipFile, manifest: JsonNode) {
        for (entry in manifest.get("entries")) {
            val path = entry.get("path").asText()
            val expectedSha256 = entry.get("sha256").asText()
            assertEquals(expectedSha256, sha256Hex(readEntryBytes(zip, path)), "sha256 mismatch for entry $path")
        }
    }

    @Test
    fun `Given a seeded account with recycled content and a real image, Then the archive is correct`() {
        // Given
        val password = "password123"
        val auth = createAuthenticatedUser(password = password)
        val seeded = seedArchiveContent(auth)

        // When: a real export, built by the real async worker
        val exportId = requestExport(auth, password)
        val state = pollUntilReady(auth, exportId)
        assertEquals("READY", state, "the export should reach READY within the bound")
        val zip = openZip(downloadBytes(auth, exportId))

        // Then
        try {
            val manifest = objectMapper.readTree(readEntryBytes(zip, "manifest.json"))
            assertManifestCounts(manifest)
            val pinLines = readJsonLines(zip, "pins.jsonl")
            assertEquals(3, pinLines.size, "one pins.jsonl line per pin")
            assertRecycledPinCarriesDeletedAt(pinLines, seeded.recycledPinId)
            assertRecycledBoardMembershipSurvives(pinLines, seeded)
            assertBoardsJsonl(zip, seeded)
            assertImageEntryIsByteIdentical(zip, pinLines, seeded.imagePinId)
            assertEveryEntryDigestMatches(zip, manifest)
        } finally {
            zip.close()
        }
    }

    // --- Erasure: account deletion destroys a READY export's row and bytes ---

    @Test
    fun `Given an account with a READY export, Then deleting the account erases the row and the file`() {
        // Given
        val password = "password123"
        val auth = createAuthenticatedUser(password = password)
        val exportId = requestExport(auth, password)
        assertEquals("READY", pollUntilReady(auth, exportId))
        val archivePath = archivePathFor(exportId)
        assertTrue(Files.exists(archivePath), "the archive file should exist on disk once READY")

        // When
        given()
            .authenticatedAs(auth)
            .header("X-Reauthentication", stepUp(password))
            .`when`().delete("/api/v1/me")
            .then().statusCode(202)

        // Then
        assertTrue(pollUntilExportGone(exportId), "the export row should be erased by the deletion worker")
        assertTrue(pollUntilFileGone(archivePath), "the archive file should be removed from disk")
    }

    /** A PENDING row whose request is past the grace, so the sweep's answer is [taskId]'s state alone. */
    private fun seedInterruptedExport(auth: IntegrationTest.AuthenticatedUser, taskId: UUID): UUID {
        val exportId = UUID.randomUUID()
        userDataExportRepository.save(
            UserDataExport(
                id = exportId,
                userId = auth.user.id,
                state = UserDataExportState.PENDING,
                formatVersion = 1,
                requestedAt = Instant.now().minus(exportsConfig.interruptedGrace()).minus(Duration.ofHours(1)),
                taskId = taskId,
            ),
        )
        return exportId
    }

    // --- Purge: driven directly through the injected ReapExpiredUserDataExports bean ---

    @Test
    fun `Given an expired READY export, Then reaping it purges the bytes, marks it EXPIRED and keeps the row`() {
        // Given: a real READY export, backdated past its retention window. Forcing exports.purge_interval
        // low enough to observe the scheduled sweep would still be a race against a fixed-delay scheduler;
        // calling the produced ReapExpiredUserDataExports bean directly is the deterministic route the
        // task brief itself points at.
        val password = "password123"
        val auth = createAuthenticatedUser(password = password)
        val exportId = requestExport(auth, password)
        assertEquals("READY", pollUntilReady(auth, exportId))
        val ready = requireNotNull(userDataExportRepository.findById(exportId))
        val archivePath = archivePathFor(exportId)
        assertTrue(Files.exists(archivePath))
        userDataExportRepository.save(ready.copy(expiresAt = Instant.now().minus(Duration.ofDays(1))))

        // When
        reapExpiredUserDataExports.reap()

        // Then
        val reaped = requireNotNull(userDataExportRepository.findById(exportId))
        assertEquals(UserDataExportState.EXPIRED, reaped.state)
        assertFalse(Files.exists(archivePath), "the archive bytes should be removed once expired")
    }

    @Test
    fun `Given a superseded export whose archive delete failed, Then the next sweep takes its bytes and its key`() {
        // Given: the requester deletes a superseded archive best-effort, after its transaction
        // (`UserDataExportRequester.kt:53`). This module has no fault injection, so the bytes are
        // written back to stand for the delete that did not happen.
        val password = "password123"
        val auth = createAuthenticatedUser(password = password)
        val supersededId = requestExport(auth, password)
        assertEquals("READY", pollUntilReady(auth, supersededId))
        val archiveBytes = downloadBytes(auth, supersededId)
        val archivePath = archivePathFor(supersededId)
        // Drained, not merely requested: this class truncates every table before each case, and a
        // build still in flight holds the single test connection while that truncation runs.
        val supersedingId = requestExport(auth, password)
        assertEquals("READY", pollUntilReady(auth, supersedingId), "the superseding build should finish")
        Files.write(archivePath, archiveBytes)
        val superseded = requireNotNull(userDataExportRepository.findById(supersededId))
        assertEquals(UserDataExportState.SUPERSEDED, superseded.state)
        assertNotNull(superseded.storageKey, "a superseded row keeps the key that names its residue")

        // When
        reapExpiredUserDataExports.reap()

        // Then
        assertFalse(Files.exists(archivePath), "the residue should leave the disk")
        assertNull(
            requireNotNull(userDataExportRepository.findById(supersededId)).storageKey,
            "the row should stop naming bytes the sweep has taken",
        )
    }

    @Test
    fun `Given two pending exports of the same age, Then only the one whose task is dead is failed`() {
        // Given: a kind no handler is registered for, which the runtime settles to DEAD on its own
        // (`TaskProcessor.kt:37-39`). A row naming no task at all would exercise "absent" instead,
        // and killing a real export task would race the live worker.
        val password = "password123"
        val auth = createAuthenticatedUser(password = password)
        val task = enqueueTask.enqueue(kind = "no.handler", payload = "{}", maxAttempts = 1)
        assertTrue(pollUntilTaskDead(task.id), "the runtime should settle a handler-less task to DEAD")
        val exportId = seedInterruptedExport(auth, task.id)
        // Spec criterion 5, same age and same sweep: a condemnation reading the grace alone takes
        // this row too. Its owner is another account, since a second PENDING row of `auth` would
        // make the request below the 409 this case exists to rule out. Its task is delayed rather
        // than running, so it stays live without a handler racing the assertion.
        val building = createAuthenticatedUser()
        val liveTask =
            enqueueTask.enqueue(kind = "no.handler", payload = "{}", maxAttempts = 1, delay = Duration.ofHours(1))
        val buildingId = seedInterruptedExport(building, liveTask.id)

        // When
        reapExpiredUserDataExports.reap()

        // Then
        val swept = requireNotNull(userDataExportRepository.findById(exportId))
        assertEquals(UserDataExportState.FAILED, swept.state)
        assertEquals("EXPORT_INTERRUPTED", swept.failureCode)
        val untouched = requireNotNull(userDataExportRepository.findById(buildingId))
        assertEquals(UserDataExportState.PENDING, untouched.state, "a row whose task is live is left alone")
        assertNull(untouched.failureCode, "a row the sweep left alone carries no failure code")
        // 202 rather than merely "not 409": this profile pins exports.minimum_interval to PT0S,
        // where production answers 429 for as long as the cooldown runs.
        val acceptedId = given()
            .authenticatedAs(auth)
            .header("X-Reauthentication", stepUp(password))
            .`when`().post("/api/v1/me/exports")
            .then().statusCode(202)
            .extract().jsonPath().getString("id")
            .let(UUID::fromString)
        // Drained for the same reason as the superseding build above: the case must not hand back
        // while the worker still holds the single test connection.
        assertEquals("READY", pollUntilReady(auth, acceptedId), "the accepted request should build")
    }

    @Test
    fun `Given a READY export, Then the downloaded body has the length and digest its row declares`() {
        // Given: a real archive, built and published by the real worker
        val password = "password123"
        val auth = createAuthenticatedUser(password = password)
        val exportId = requestExport(auth, password)
        assertEquals("READY", pollUntilReady(auth, exportId))

        // When
        val body = downloadBytes(auth, exportId)

        // Then: the archive itself, not the entries inside it, which a losing attempt's bytes would
        // satisfy just as well once they had overwritten the canonical key
        val row = requireNotNull(userDataExportRepository.findById(exportId))
        assertEquals(row.byteSize, body.size.toLong(), "the served archive should be as long as its row declares")
        assertEquals(row.sha256, sha256Hex(body), "the served archive should hash to what its row declares")
    }

    // --- Observability: the line an operator reads, on the channel it is read from ---

    /** slf4j binds to the JBoss LogManager, which is the JUL one, so a plain JUL handler sees the line. */
    private fun capturingSweepLogs(sweep: () -> Unit): List<String> {
        val messages = mutableListOf<String>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    messages += record.message.orEmpty()
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        val logger = Logger.getLogger(ExportRetentionLifecycle::class.java.name)
        logger.addHandler(handler)
        try {
            sweep()
        } finally {
            logger.removeHandler(handler)
        }
        return messages
    }

    /** A row already past its expiry, so one sweep expires it and then reclaims whatever it names. */
    private fun seedExpiredReadyExport(auth: IntegrationTest.AuthenticatedUser, withBytes: Boolean) {
        val exportId = UUID.randomUUID()
        val storageKey = "exports/$exportId.zip".takeIf { withBytes }
        storageKey?.let {
            val path = Path.of(exportsConfig.dataDir()).resolve(it)
            Files.createDirectories(path.parent)
            Files.write(path, "an archive past its retention".toByteArray())
        }
        userDataExportRepository.save(
            UserDataExport(
                id = exportId,
                userId = auth.user.id,
                state = UserDataExportState.READY,
                formatVersion = 1,
                requestedAt = Instant.now().minus(Duration.ofDays(8)),
                completedAt = Instant.now().minus(Duration.ofDays(8)),
                expiresAt = Instant.now().minus(Duration.ofDays(1)),
                storageKey = storageKey,
            ),
        )
    }

    @Test
    fun `Given a sweep the lifecycle guards, Then what each pass moved reaches the log`() {
        // Given: three counts no two of which are equal, so a line naming them in another pass's
        // order cannot pass. Only a row still naming bytes is reclaimable, which is what parts the
        // second count from the third.
        val auth = createAuthenticatedUser()
        seedExpiredReadyExport(auth, withBytes = true)
        seedExpiredReadyExport(auth, withBytes = false)

        // When
        val logged = capturingSweepLogs { exportRetentionLifecycle.safeReap() }

        // Then
        assertEquals(1, logged.size, "one line per sweep, got $logged")
        val eachCount = listOf("0 failed", "2 expired", "1 reclaimed")
        assertTrue(
            eachCount.all { logged.first().contains(it) },
            "the sweep line must name every pass count, got: ${logged.first()}",
        )
    }

    // --- Headers come from the stored row, not the adapter's current format ---

    @Test
    fun `Given a stored export with a divergent media type, Then downloading it serves the stored headers`() {
        // Given: persisted directly through the injected repository, since the real adapter always
        // produces application/zip -- there is no way to make a real build diverge from that.
        val auth = createAuthenticatedUser()
        val exportId = UUID.randomUUID()
        val content = "hello divergent export".toByteArray()
        val storageKey = "exports/$exportId.txt"
        val archivePath = Path.of(exportsConfig.dataDir()).resolve(storageKey)
        Files.createDirectories(archivePath.parent)
        Files.write(archivePath, content)
        val sha256 = sha256Hex(content)
        userDataExportRepository.save(
            UserDataExport(
                id = exportId,
                userId = auth.user.id,
                state = UserDataExportState.READY,
                formatVersion = 1,
                requestedAt = Instant.now(),
                completedAt = Instant.now(),
                expiresAt = Instant.now().plus(Duration.ofDays(1)),
                storageKey = storageKey,
                byteSize = content.size.toLong(),
                sha256 = sha256,
                mediaType = "text/plain",
                fileExtension = "txt",
            ),
        )

        // When
        val extracted = given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/me/exports/$exportId/download")
            .then().statusCode(200)
            .extract()

        // Then
        assertEquals("text/plain", extracted.header("Content-Type"))
        assertEquals("\"$sha256\"", extracted.header("ETag"))
        assertTrue(
            extracted.header("Content-Disposition").contains(".txt"),
            "Content-Disposition should carry the stored file extension, not the adapter's",
        )
        assertArrayEquals(content, extracted.asByteArray())
    }

    private companion object {
        const val POLL_ATTEMPTS = 50
        const val POLL_INTERVAL_MS = 200L
    }
}
