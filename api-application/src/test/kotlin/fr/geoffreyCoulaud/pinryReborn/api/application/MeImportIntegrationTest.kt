package fr.geoffreyCoulaud.pinryReborn.api.application

import com.fasterxml.jackson.databind.ObjectMapper
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ImagesConfig
import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.EnqueueTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.UserDataImportTask
import fr.geoffreyCoulaud.pinryReborn.api.worker.ImportsConfig
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.io.path.exists

/**
 * `/api/v1/me/imports` end to end (spec `docs/specs/2026-08-14-user-data-import.md` section 13), over the
 * real REST surface, archive store, libvips probe and async worker. The round trip is the point of it.
 */
@QuarkusTest
@TestProfile(MeImportTestProfile::class)
// The app under test runs the real SystemClock; these read the wall clock to keep fixtures consistent with it.
@Suppress("WallClockRead")
class MeImportIntegrationTest : IntegrationTest() {
    @Inject lateinit var pinCreator: PinCreator

    @Inject lateinit var boardCreator: BoardCreator

    @Inject lateinit var pinRepository: PinRepositoryInterface

    @Inject lateinit var boardRepository: BoardRepositoryInterface

    @Inject lateinit var tagRepository: TagRepositoryInterface

    @Inject lateinit var imageRepository: ImageRepositoryInterface

    @Inject lateinit var importRepository: UserDataImportRepositoryInterface

    @Inject lateinit var taskQueue: TaskQueueInterface

    @Inject lateinit var enqueueTask: EnqueueTask

    @Inject lateinit var imageStore: ImageStore

    @Inject lateinit var importsConfig: ImportsConfig

    @Inject lateinit var imagesConfig: ImagesConfig

    @Inject lateinit var objectMapper: ObjectMapper

    // --- The wire path: open, upload, complete, poll ---

    private fun openImport(auth: AuthenticatedUser): UUID =
        given()
            .authenticatedAs(auth)
            .`when`().post("/api/v1/me/imports")
            .then().statusCode(202)
            .extract().jsonPath().getString("id")
            .let(UUID::fromString)

    private fun uploadChunk(auth: AuthenticatedUser, importId: UUID, bytes: ByteArray, offset: Long): Response =
        given()
            .authenticatedAs(auth)
            .contentType("application/octet-stream")
            .body(bytes)
            .`when`().put("/api/v1/me/imports/$importId/archive?offset=$offset")

    private fun completeArchive(auth: AuthenticatedUser, importId: UUID) {
        given()
            .authenticatedAs(auth)
            .`when`().post("/api/v1/me/imports/$importId/archive/complete")
            .then().statusCode(202).body("state", equalTo("PENDING"))
    }

    private fun importState(auth: AuthenticatedUser, importId: UUID): String =
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/me/imports/$importId")
            .then().statusCode(200)
            .extract().jsonPath().getString("state")

    /** Bounded poll until the row stops moving; the last state observed is what the caller asserts. */
    private fun pollUntilSettled(auth: AuthenticatedUser, importId: UUID): String {
        var last = "UNKNOWN"
        repeat(POLL_ATTEMPTS) {
            last = importState(auth, importId)
            if (last in TERMINAL_STATES) return last
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return last
    }

    /** The whole wire path in one chunk, for every case that is not about the upload itself. */
    private fun importArchive(auth: AuthenticatedUser, archive: ByteArray): UUID {
        val importId = openImport(auth)
        uploadChunk(auth, importId, archive, 0).then().statusCode(200)
        completeArchive(auth, importId)
        assertEquals("COMPLETED", pollUntilSettled(auth, importId), "the import should complete within the bound")
        return importId
    }

    private fun issueKinds(auth: AuthenticatedUser, importId: UUID): List<String> =
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/me/imports/$importId/issues?pageSize=$ISSUE_PAGE_SIZE")
            .then().statusCode(200)
            .extract().jsonPath().getList("issues.kind", String::class.java)

    private fun counters(auth: AuthenticatedUser, importId: UUID) =
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/me/imports/$importId")
            .then().statusCode(200)
            .extract().jsonPath()

    // --- Seeding, and the real export the round trip pours back in ---

    private fun fixture(name: String) = File("src/test/resources/fixtures/$name")

    private fun stepUp(password: String) =
        "password " + Base64.getUrlEncoder().encodeToString(password.toByteArray())

    private fun uploadImage(auth: AuthenticatedUser, pinId: UUID, name: String, mediaType: String) {
        given()
            .authenticatedAs(auth)
            .multiPart("file", fixture(name), mediaType)
            .`when`().put("/api/v1/pins/$pinId/image")
            .then().statusCode(201)
    }

    private fun putPinInBoards(auth: AuthenticatedUser, pinId: UUID, boardIds: List<UUID>) {
        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"boardIds": [${boardIds.joinToString(",") { "\"$it\"" }}]}""")
            .`when`().put("/api/v1/pins/$pinId/boards")
            .then().statusCode(200)
    }

    private fun createPin(auth: AuthenticatedUser, slug: String, tags: List<String> = emptyList()) =
        pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.test/$slug",
            sourceMediaUrl = "https://example.test/$slug.jpg",
            description = "Pin $slug",
            tags = tags,
        )

    /**
     * Two active pins, one recycled pin, an active board, a recycled board holding a pin, two tags,
     * and a fourth pin sharing the first one's medium byte for byte (spec section 13.1).
     */
    private fun seedRoundTripContent(auth: AuthenticatedUser) {
        val alpha = createPin(auth, ALPHA, tags = listOf("nature", "travel"))
        val beta = createPin(auth, BETA, tags = listOf("nature"))
        val gamma = createPin(auth, GAMMA)
        val delta = createPin(auth, DELTA)
        uploadImage(auth, alpha.id, "sample.png", "image/png")
        uploadImage(auth, beta.id, "sample.jpg", "image/jpeg")
        uploadImage(auth, gamma.id, "animated.gif", "image/gif")
        // Beta's medium, not alpha's: the pair collapses into whichever line the archive lists first, so
        // the pin carrying the recycled membership must not be one of the two.
        uploadImage(auth, delta.id, "sample.jpg", "image/jpeg")
        val activeBoard = boardCreator.create(auth.user, "Active board", "kept")
        val recycledBoard = boardCreator.create(auth.user, "Recycled board", "recycled")
        putPinInBoards(auth, alpha.id, listOf(activeBoard.id, recycledBoard.id))
        given().authenticatedAs(auth).`when`().delete("/api/v1/boards/${recycledBoard.id}").then().statusCode(204)
        given().authenticatedAs(auth).`when`().delete("/api/v1/pins/${gamma.id}").then().statusCode(204)
    }

    /** A real export, built by the real worker and downloaded over the wire. */
    private fun exportArchiveOf(auth: AuthenticatedUser, password: String): ByteArray {
        val exportId =
            given()
                .authenticatedAs(auth)
                .header("X-Reauthentication", stepUp(password))
                .`when`().post("/api/v1/me/exports")
                .then().statusCode(202)
                .extract().jsonPath().getString("id")
        repeat(POLL_ATTEMPTS) {
            val state =
                given()
                    .authenticatedAs(auth)
                    .`when`().get("/api/v1/me/exports/$exportId")
                    .then().extract().jsonPath().getString("state")
            if (state == "READY") return downloadExport(auth, exportId)
            Thread.sleep(POLL_INTERVAL_MS)
        }
        error("the export never reached READY")
    }

    private fun downloadExport(auth: AuthenticatedUser, exportId: String): ByteArray =
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/me/exports/$exportId/download")
            .then().statusCode(200)
            .extract().asByteArray()

    // --- What an account holds, in a shape two accounts can be compared on ---

    private data class PinFacts(
        val id: UUID,
        val imageId: UUID,
        val description: String,
        val sourceMediaUrl: String?,
        val createdAt: Instant,
        val updatedAt: Instant,
        val deletedAt: Instant?,
        val tagNames: Set<String>,
        val boardNames: Set<String>,
        val imageBytes: ByteArray,
    )

    private data class AccountFacts(
        val pins: Map<String, PinFacts>,
        val boards: Map<String, Instant?>,
        val tags: Set<String>,
    )

    private fun factsOf(pin: Pin): PinFacts {
        val image = requireNotNull(imageRepository.findByPinId(pin.id)) { "pin ${pin.id} carries no image" }
        return PinFacts(
            id = pin.id,
            imageId = image.id,
            description = pin.description,
            sourceMediaUrl = pin.sourceMediaUrl,
            createdAt = pin.createdAt,
            updatedAt = pin.updatedAt,
            deletedAt = pin.softDeletedAt,
            tagNames = pin.tags.map { it.name }.toSet(),
            // Including the recycled ones, which is the membership the round trip is really about.
            boardNames = pinRepository.findBoardsForPinIncludingRecycled(pin.id).map { it.name }.toSet(),
            imageBytes = imageStore.openStream(image.storageKey).use { it.readBytes() },
        )
    }

    private fun factsOf(user: User): AccountFacts {
        val pins = pinRepository.findAllPinsForUser(user) + pinRepository.findAllSoftDeletedPinsForUser(user)
        val boards = boardRepository.findActiveBoardsForUser(user) + boardRepository.findRecycledBoardsForUser(user)
        return AccountFacts(
            pins = pins.associate { it.sourceContextUrl to factsOf(it) },
            boards = boards.associate { it.name to it.softDeletedAt },
            tags = tagRepository.findAllTagsForUser(user).map { it.name }.toSet(),
        )
    }

    /** Spec section 13.1's enumeration: the word "equivalent" is not an assertion, so this is the list. */
    private fun assertSamePin(source: PinFacts, imported: PinFacts) {
        assertEquals(source.description, imported.description)
        assertEquals(source.sourceMediaUrl, imported.sourceMediaUrl)
        assertEquals(source.createdAt, imported.createdAt)
        assertEquals(source.updatedAt, imported.updatedAt)
        assertEquals(source.deletedAt, imported.deletedAt)
        assertEquals(source.tagNames, imported.tagNames)
        assertEquals(source.boardNames, imported.boardNames)
        assertArrayEquals(source.imageBytes, imported.imageBytes, "the medium should survive byte for byte")
        assertNotEquals(source.id, imported.id, "the copy must be a new row, never the same identifier")
        assertNotEquals(source.imageId, imported.imageId)
    }

    // --- The round trip ---

    @Test
    fun `Given a real archive poured into a second account, Then every field survives and no identifier does`() {
        // Given: the destination account first, since the import clamps every restored instant to that
        // account's creation, and one created later would flatten all of them onto its own birth.
        // Nothing is wiped: deleting the origin would destroy the very archive under test.
        val password = DEFAULT_PASSWORD
        val destination = createAuthenticatedUser()
        val origin = createAuthenticatedUser(password = password)
        seedRoundTripContent(origin)

        // When: a real export, downloaded over the wire and uploaded into the empty account
        val archive = exportArchiveOf(origin, password)
        val importId = importArchive(destination, archive)

        // Then
        val source = factsOf(origin.user)
        val copy = factsOf(destination.user)
        assertEquals(source.pins.size - 1, copy.pins.size, "the two pins sharing a medium import as one")
        val fromTheSharedMedium = copy.pins.keys.count { it.endsWith("/$BETA") || it.endsWith("/$DELTA") }
        assertEquals(1, fromTheSharedMedium, "one of the two lines is skipped, not reported as ambiguous")
        copy.pins.forEach { (sourceContextUrl, imported) ->
            assertSamePin(source.pins.getValue(sourceContextUrl), imported)
        }
        // Named rather than left to the loop: which of the two shared-medium lines survives is the
        // archive's order to decide, and this membership is what the round trip is really about.
        assertEquals(
            setOf("Active board", "Recycled board"),
            copy.pins.getValue("https://example.test/$ALPHA").boardNames,
        )
        assertEquals(source.boards, copy.boards, "both boards, the recycled one still recycled")
        assertEquals(source.tags, copy.tags)
        assertFalse(issueKinds(destination, importId).contains("MEDIA_AMBIGUOUS"), "a skip is not an ambiguity")
        val counters = counters(destination, importId)
        assertEquals(3, counters.getInt("createdPins"))
        assertEquals(1, counters.getInt("skippedPins"))
    }

    // --- The upload itself ---

    @Test
    fun `Given a replayed offset, Then the refusal names the current length the upload resumes from`() {
        // Given: one small archive cut into three chunks
        val auth = createAuthenticatedUser()
        val archive = oneGoodPinArchive()
        val chunks = archive.toList().chunked((archive.size + 2) / 3).map { it.toByteArray() }
        assertEquals(3, chunks.size)
        val importId = openImport(auth)

        // When: two chunks land, the second is replayed at an offset the upload has passed
        uploadChunk(auth, importId, chunks[0], 0).then().statusCode(200)
        val afterSecond = uploadChunk(auth, importId, chunks[1], chunks[0].size.toLong())
            .then().statusCode(200).extract().jsonPath()
        val replayed = uploadChunk(auth, importId, chunks[1], chunks[0].size.toLong())
            .then().statusCode(409).body("code", equalTo("IMPORT_CHUNK_OFFSET_MISMATCH")).extract().jsonPath()

        // Then: the client resumes from the length the refusal reported, and the archive still reads.
        // Read off the problem's own member, since a number a client has to parse out of an English
        // sentence is not a contract.
        val reportedLength = replayed.getLong("currentLength")
        assertEquals(afterSecond.getLong("uploadedBytes"), reportedLength)
        uploadChunk(auth, importId, chunks[2], reportedLength).then().statusCode(200)
        completeArchive(auth, importId)
        assertEquals("COMPLETED", pollUntilSettled(auth, importId))
        assertEquals(1, pinRepository.findAllPinsForUser(auth.user).size)
    }

    @Test
    fun `Given a chunk carrying the upload past the maximum, Then it is refused and the length holds`() {
        // Given: the upload filled to imports.max_archive_bytes exactly
        val auth = createAuthenticatedUser()
        val importId = openImport(auth)
        val toTheBound = ByteArray(importsConfig.maxArchiveBytes().toInt())
        uploadChunk(auth, importId, toTheBound, 0).then().statusCode(200)

        // When: one byte more
        val refused = uploadChunk(auth, importId, ByteArray(1), toTheBound.size.toLong())

        // Then: over the wire, since the use-case case for this stubs the store that raises it
        refused
            .then().statusCode(413)
            .contentType("application/problem+json")
            .body("code", equalTo("IMPORT_ARCHIVE_TOO_LARGE"))
        assertEquals(
            toTheBound.size.toLong(),
            importRepository.findById(importId)?.uploadedBytes,
            "a refused chunk leaves the length as it was, so the client resumes rather than restarts",
        )
    }

    // --- One archive, one of every anomaly ---

    @Test
    fun `Given an archive of anomalies, Then it completes, keeps the good pin and reports each fault once`() {
        // Given
        val auth = createAuthenticatedUser()
        val png = fixture("sample.png").readBytes()
        val text = fixture("not-an-image.txt").readBytes()
        val archive =
            ImportArchiveBuilder(objectMapper)
                .manifest(announcedPins = 5)
                .tags("nature")
                .boards(ImportArchiveBuilder.boardLine(name = "a".repeat(OVER_LONG_NAME)))
                .entry("images/good.png", png)
                .entry("images/text.jpg", text)
                .pins(
                    ImportArchiveBuilder.pinLine(
                        sourceContextUrl = "https://example.test/good",
                        tags = listOf("nature"),
                        imagePath = "images/good.png",
                        imageSha256 = ImportArchiveBuilder.sha256(png),
                    ),
                    ImportArchiveBuilder.pinLine("https://example.test/traversal", imagePath = "../escape.png"),
                    ImportArchiveBuilder.pinLine("https://example.test/absent", imagePath = "images/absent.png"),
                    ImportArchiveBuilder.pinLine(
                        sourceContextUrl = "https://example.test/text",
                        imagePath = "images/text.jpg",
                        imageSha256 = ImportArchiveBuilder.sha256(text),
                    ),
                    ImportArchiveBuilder.pinLine("https://example.test/nomedia"),
                ).appendLine("pins.jsonl", "{\"description\": \"cut in ha")
                .bytes()

        // When
        val importId = importArchive(auth, archive)

        // Then: the whole report, so a seventh issue no one asked for fails here. Sorted rather than
        // in the walk's order, which is the report's paging to decide and not what this case is about.
        assertEquals(EXPECTED_ANOMALIES.sorted(), issueKinds(auth, importId).sorted())
        assertEquals(listOf("https://example.test/good"), pinRepository.findAllPinsForUser(auth.user)
            .map { it.sourceContextUrl })
        assertTrue(boardRepository.findActiveBoardsForUser(auth.user).isEmpty(), "the over-long name is refused")
    }

    // --- The manifest is never the authority ---

    @Test
    fun `Given a line lying about its medium, Then the bytes decide the type and the digest is reported`() {
        // Given: PNG bytes announced as JPEG, under a digest that is not theirs
        val auth = createAuthenticatedUser()
        val png = fixture("sample.png").readBytes()
        val archive =
            ImportArchiveBuilder(objectMapper)
                .manifest(announcedPins = 1)
                .tags()
                .boards()
                .entry("images/lying.png", png)
                .pins(
                    ImportArchiveBuilder.pinLine(
                        sourceContextUrl = "https://example.test/lying",
                        imagePath = "images/lying.png",
                        imageSha256 = ImportArchiveBuilder.sha256("not these bytes".toByteArray()),
                        imageMimeType = "image/jpeg",
                    ),
                ).bytes()

        // When
        val importId = importArchive(auth, archive)

        // Then
        val pin = pinRepository.findAllPinsForUser(auth.user).single()
        val image = requireNotNull(imageRepository.findByPinId(pin.id))
        assertEquals("image/png", image.mimeType, "the probe decides the stored type, never the archive")
        assertEquals(listOf("MEDIA_DIGEST_MISMATCH"), issueKinds(auth, importId))
        assertArrayEquals(png, imageStore.openStream(image.storageKey).use { it.readBytes() })
    }

    // --- A medium two pins already hold ---

    @Test
    fun `Given two pins already holding the medium, Then the line is ambiguous and nothing is written`() {
        // Given: the same bytes under two pins of the target account
        val auth = createAuthenticatedUser()
        val png = fixture("sample.png").readBytes()
        listOf("first", "second").forEach { slug ->
            uploadImage(auth, createPin(auth, slug).id, "sample.png", "image/png")
        }
        val storedBefore = storedObjectCount(auth.user.id)
        val archive =
            ImportArchiveBuilder(objectMapper)
                .manifest(announcedPins = 1)
                .tags()
                .boards()
                .entry("images/ambiguous.png", png)
                .pins(
                    ImportArchiveBuilder.pinLine(
                        sourceContextUrl = "https://example.test/ambiguous",
                        imagePath = "images/ambiguous.png",
                        imageSha256 = ImportArchiveBuilder.sha256(png),
                    ),
                ).bytes()

        // When
        val importId = importArchive(auth, archive)

        // Then: no row, no promoted object, no staged temp file
        assertEquals(listOf("MEDIA_AMBIGUOUS"), issueKinds(auth, importId))
        assertEquals(2, pinRepository.findAllPinsForUser(auth.user).size)
        assertEquals(2, storedBefore, "the two uploads are what the count compares against")
        assertEquals(storedBefore, storedObjectCount(auth.user.id), "an ambiguous line promotes nothing")
        assertTrue(stagedFiles().isEmpty(), "an ambiguous line stages nothing")
    }

    // --- An account that is not empty ---

    @Test
    fun `Given names the account already holds in another case, Then nothing is created and nothing changes`() {
        // Given: the account holds tag `voyage` and board `Summer`, the archive carries `Voyage` and `summer`
        val auth = createAuthenticatedUser()
        val pin = createPin(auth, "held", tags = listOf("voyage"))
        val board = boardCreator.create(auth.user, "Summer", "the account's own")
        putPinInBoards(auth, pin.id, listOf(board.id))
        val archive =
            ImportArchiveBuilder(objectMapper)
                .manifest(announcedPins = 0)
                .tags("Voyage")
                .boards(ImportArchiveBuilder.boardLine(name = "summer", description = "the archive's"))
                .pins()
                .bytes()

        // When
        val importId = importArchive(auth, archive)

        // Then
        val counters = counters(auth, importId)
        assertEquals(0, counters.getInt("createdTags"))
        assertEquals(1, counters.getInt("skippedTags"))
        assertEquals(0, counters.getInt("createdBoards"))
        assertEquals(1, counters.getInt("skippedBoards"))
        assertEquals(setOf("voyage"), tagRepository.findAllTagsForUser(auth.user).map { it.name }.toSet())
        val kept = boardRepository.findActiveBoardsForUser(auth.user).single()
        assertEquals(board.name, kept.name)
        assertEquals(board.description, kept.description)
        assertEquals(board.updatedAt, kept.updatedAt, "a skipped board is left untouched")
        assertEquals(setOf(board.name), pinRepository.findBoardsForPinIncludingRecycled(pin.id).map { it.name }.toSet())
    }

    @Test
    fun `Given a recycled board holding the name, Then the archive's board is refused and the bin is untouched`() {
        // Given
        val auth = createAuthenticatedUser()
        val board = boardCreator.create(auth.user, "Summer", "recycled")
        given().authenticatedAs(auth).`when`().delete("/api/v1/boards/${board.id}").then().statusCode(204)
        val recycled = boardRepository.findRecycledBoardsForUser(auth.user).single()
        val archive =
            ImportArchiveBuilder(objectMapper)
                .manifest(announcedPins = 0)
                .tags()
                .boards(ImportArchiveBuilder.boardLine(name = "Summer", description = "the archive's"))
                .pins()
                .bytes()

        // When
        val importId = importArchive(auth, archive)

        // Then
        assertEquals(listOf("NAME_TAKEN_BY_RECYCLED"), issueKinds(auth, importId))
        assertTrue(boardRepository.findActiveBoardsForUser(auth.user).isEmpty(), "nothing is created")
        assertEquals(recycled, boardRepository.findRecycledBoardsForUser(auth.user).single())
    }

    // --- Cancellation, and the wire's error format ---

    @Test
    fun `Given an import still awaiting its archive, Then cancelling it drops the partial upload`() {
        // Given: one chunk on disk under the upload path, and no task, which this phase never has
        val auth = createAuthenticatedUser()
        val importId = openImport(auth)
        uploadChunk(auth, importId, oneGoodPinArchive(), 0).then().statusCode(200)
        val uploadPath = Path.of(importsConfig.dataDir()).resolve("tmp/import-$importId.part")
        assertTrue(uploadPath.exists(), "the chunk is what the cancellation has to reclaim")

        // When
        given().authenticatedAs(auth).`when`().delete("/api/v1/me/imports/$importId").then().statusCode(204)

        // Then
        val cancelled = requireNotNull(importRepository.findById(importId))
        assertEquals(UserDataImportState.CANCELLED, cancelled.state)
        assertNull(cancelled.taskId)
        assertEquals(0, taskQueue.countByState(TaskState.PENDING), "nothing was queued to cancel")
        assertFalse(uploadPath.exists(), "the partial upload of a cancelled import is reclaimed")
    }

    @Test
    fun `Given a PENDING import, Then cancelling it cancels the task and reclaims the archive`() {
        // Given: the task is enqueued far enough out that the real worker cannot claim it first, which
        // is the only deterministic way to observe a PENDING row (a small archive finishes in
        // milliseconds, and spec section 13.6 refuses to paper that over with a sleep).
        val auth = createAuthenticatedUser()
        val importId = openImport(auth)
        val storageKey = "imports/$importId.zip"
        val archivePath = Path.of(importsConfig.dataDir()).resolve(storageKey)
        Files.createDirectories(archivePath.parent)
        Files.write(archivePath, oneGoodPinArchive())
        val task = enqueueTask.enqueue(
            kind = UserDataImportTask.KIND,
            payload = importId.toString(),
            maxAttempts = UserDataImportTask.MAX_ATTEMPTS,
            delay = Duration.ofHours(1),
        )
        val opened = requireNotNull(importRepository.findById(importId))
        importRepository.save(
            opened.copy(state = UserDataImportState.PENDING, taskId = task.id, storageKey = storageKey),
        )

        // When
        given().authenticatedAs(auth).`when`().delete("/api/v1/me/imports/$importId").then().statusCode(204)

        // Then
        assertEquals(UserDataImportState.CANCELLED, requireNotNull(importRepository.findById(importId)).state)
        assertEquals(TaskState.CANCELLED, requireNotNull(taskQueue.findById(task.id)).state)
        assertFalse(archivePath.exists(), "the archive of a cancelled import is reclaimed")
    }

    @Test
    fun `Given an unknown import id, Then the refusal is problem json carrying its code`() {
        // Given
        val auth = createAuthenticatedUser()

        // When / Then
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/me/imports/${UUID.randomUUID()}")
            .then().statusCode(404)
            .contentType("application/problem+json")
            .body("code", equalTo("IMPORT_DOES_NOT_EXIST"))
            .body("status", equalTo(404))
    }

    // --- Fixtures shared by several cases ---

    private fun oneGoodPinArchive(): ByteArray {
        val png = fixture("sample.png").readBytes()
        return ImportArchiveBuilder(objectMapper)
            .manifest(announcedPins = 1)
            .tags("nature")
            .boards()
            .entry("images/only.png", png)
            .pins(
                ImportArchiveBuilder.pinLine(
                    sourceContextUrl = "https://example.test/only",
                    tags = listOf("nature"),
                    imagePath = "images/only.png",
                    imageSha256 = ImportArchiveBuilder.sha256(png),
                ),
            ).bytes()
    }

    /** Scoped to the account: the data directory outlives a case, since only the database is truncated. */
    private fun storedObjectCount(userId: UUID): Int =
        countFiles(Path.of(imagesConfig.dataDir()).resolve("originals").resolve(userId.toString()))

    private fun stagedFiles(): List<Path> = listFiles(Path.of(imagesConfig.dataDir()).resolve("tmp"))

    private fun countFiles(root: Path): Int = listFiles(root).size

    private fun listFiles(root: Path): List<Path> =
        when {
            !root.exists() -> emptyList()
            else -> Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
        }

    private companion object {
        const val POLL_ATTEMPTS = 50
        const val POLL_INTERVAL_MS = 200L
        const val ISSUE_PAGE_SIZE = 100
        const val OVER_LONG_NAME = 300
        const val ALPHA = "alpha"
        const val BETA = "beta"
        const val GAMMA = "gamma"
        const val DELTA = "delta"
        val TERMINAL_STATES = setOf("COMPLETED", "FAILED", "CANCELLED", "ABANDONED")
        val EXPECTED_ANOMALIES = listOf(
            "ENTRY_PATH_INVALID",
            "LINE_MALFORMED",
            "MEDIA_ENTRY_MISSING",
            "MEDIA_UNREADABLE",
            "PIN_HAS_NO_MEDIA",
            "FIELD_INVALID",
        )
    }
}
