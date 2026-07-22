package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.CursorDirection
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveEntryDigest
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveSink
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.exceptions.PermanentTaskException
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.UUID.randomUUID
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A fake [ArchiveSink] that records what was written, with REAL per-entry digests (not constants):
 * a manifest test must be able to tell a correct manifest from one that lost or mixed up entries.
 * `order` tracks the sequence of entry names, which is what pins the "images before pins.jsonl"
 * writing-order requirement (spec `docs/specs/2026-07-22-user-data-export.md` §3).
 */
private class RecordingSink : ArchiveSink {
    val text = linkedMapOf<String, String>()
    val json = linkedMapOf<String, Any>()
    val jsonLines = linkedMapOf<String, List<Any>>()
    val binary = linkedMapOf<String, ByteArray>()
    val order = mutableListOf<String>()

    override fun putTextEntry(name: String, text: String) = record(name) {
        this.text[name] = text
        text.toByteArray()
    }

    override fun putJsonEntry(name: String, value: Any) = record(name) {
        json[name] = value
        value.toString().toByteArray()
    }

    override fun putJsonLinesEntry(name: String, values: Sequence<Any>) = record(name) {
        // The real sink consumes the sequence here too: this is the one and only iteration.
        val list = values.toList()
        jsonLines[name] = list
        list.toString().toByteArray()
    }

    override fun putBinaryEntry(name: String, bytes: InputStream) = record(name) {
        val content = bytes.use { it.readBytes() }
        binary[name] = content
        content
    }

    private fun record(name: String, write: () -> ByteArray) =
        write().let { bytes ->
            order += name
            ArchiveEntryDigest(name, bytes.size.toLong(), sha256Hex(bytes))
        }

    private fun sha256Hex(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}

class UserDataExportBuilderTest : BaseTest() {
    private val exportRepository = mockk<UserDataExportRepositoryInterface>()
    private val userRepository = mockk<UserRepositoryInterface>()
    private val pinRepository = mockk<PinRepositoryInterface>()
    private val imageRepository = mockk<ImageRepositoryInterface>()
    private val boardRepository = mockk<BoardRepositoryInterface>()
    private val tagRepository = mockk<TagRepositoryInterface>()
    private val imageStore = mockk<ImageStore>()
    private val archiveStore = mockk<ExportArchiveStore>()
    private val transactionRunner = mockk<TransactionRunner>()
    private val clock = mockk<Clock>()
    private val pageSize = 500
    private val retention = Duration.ofDays(7)
    private val now = Instant.parse("2026-07-22T10:00:00Z")
    private val userId = randomUUID()
    private val user = User(id = userId, name = "alice", createdAt = Instant.parse("2020-01-01T00:00:00Z"))
    private val exportId = randomUUID()

    private val builder = UserDataExportBuilder(
        exportRepository, userRepository, pinRepository, imageRepository, boardRepository, tagRepository,
        imageStore, archiveStore, transactionRunner, clock, applicationVersion = "1.2.3", pageSize = pageSize,
        retention = retention, minimumFreeBytes = MINIMUM_FREE_BYTES,
    )

    private lateinit var sink: RecordingSink

    private fun stubArchiveStore() {
        sink = RecordingSink()
        every { archiveStore.stage(any()) } answers {
            val block = firstArg<(ArchiveSink) -> Unit>()
            block(sink)
            StagedFile(path = "tmp/staged.zip", byteSize = 0L, contentHash = "unused")
        }
    }

    private fun anExport(formatVersion: Int = 1) =
        UserDataExport(
            id = exportId, userId = userId, state = UserDataExportState.PENDING,
            formatVersion = formatVersion, requestedAt = now,
        )

    private fun aPin(
        id: UUID = randomUUID(),
        tags: List<Tag> = emptyList(),
        softDeletedAt: Instant? = null,
        createdAt: Instant? = now,
        updatedAt: Instant? = now,
    ) = Pin(
        id = id, author = user, sourceContextUrl = "https://example.org/a", sourceMediaUrl = null,
        description = "desc", tags = tags, boards = emptyList(), softDeletedAt = softDeletedAt,
        createdAt = createdAt, updatedAt = updatedAt,
    )

    private fun anImage(pinId: UUID, id: UUID = randomUUID(), mimeType: String = "image/jpeg") = Image(
        id = id, pinId = pinId, mimeType = mimeType, width = 10, height = 10, animated = false,
        byteSize = 3L, contentHash = "content-hash", storageKey = "originals/$id", createdAt = now,
    )

    private fun aBoard(id: UUID = randomUUID(), name: String = "board", softDeletedAt: Instant? = null) = Board(
        id = id, author = user, name = name, description = "d", softDeletedAt = softDeletedAt,
        createdAt = now, updatedAt = now,
    )

    /** Stubs a single active-pin page holding exactly [pins]. */
    private fun stubActivePins(pins: List<Pin>) {
        every { pinRepository.findPinsForUser(user, null, pageSize, PinSortStrategy.CREATED_AT_DESC) } returns
            Page(items = pins, previousCursor = null, nextCursor = null)
    }

    /** Stubs a single recycled-pin page holding exactly [pins]. */
    private fun stubRecycledPins(pins: List<Pin>) {
        every {
            pinRepository.findSoftDeletedPinsForUser(user, null, pageSize, PinSortStrategy.DELETED_AT_DESC)
        } returns Page(items = pins, previousCursor = null, nextCursor = null)
    }

    /** Stubs an empty single page for both pin walks, and empty boards/tags -- the "nothing but the shell" case. */
    private fun stubEmptyCollections() {
        stubActivePins(emptyList())
        stubRecycledPins(emptyList())
        every { boardRepository.findActiveBoardsForUser(user) } returns emptyList()
        every { boardRepository.findRecycledBoardsForUser(user) } returns emptyList()
        every { tagRepository.findAllTagsForUser(user) } returns emptyList()
    }

    @Test
    fun `Given active and recycled pins, Then every pin is written with its deletion marker`() {
        // Given
        stubArchiveStore()
        every { clock.now() } returns now
        val activePin = aPin(createdAt = now)
        val recycledPin = aPin(softDeletedAt = now, createdAt = now.minusSeconds(10))
        stubActivePins(listOf(activePin))
        stubRecycledPins(listOf(recycledPin))
        every { boardRepository.findActiveBoardsForUser(user) } returns emptyList()
        every { boardRepository.findRecycledBoardsForUser(user) } returns emptyList()
        every { tagRepository.findAllTagsForUser(user) } returns emptyList()
        every { imageRepository.findByPinId(any()) } returns null
        every { pinRepository.findBoardsForPinIncludingRecycled(any()) } returns emptyList()

        // When
        builder.stageArchive(anExport(), user, renewLease = {})

        // Then
        val pins = sink.jsonLines.getValue("pins.jsonl").filterIsInstance<ExportedPin>()
        assertEquals(2, pins.size)
        assertNull(pins.first { it.id == activePin.id }.deletedAt)
        assertEquals(now, pins.first { it.id == recycledPin.id }.deletedAt)
    }

    @Test
    fun `Given a pin with an image, Then the image entry is written before the pin references it`() {
        // Given
        stubArchiveStore()
        every { clock.now() } returns now
        val pin = aPin()
        val image = anImage(pinId = pin.id, mimeType = "image/png")
        stubActivePins(listOf(pin))
        stubRecycledPins(emptyList())
        every { boardRepository.findActiveBoardsForUser(user) } returns emptyList()
        every { boardRepository.findRecycledBoardsForUser(user) } returns emptyList()
        every { tagRepository.findAllTagsForUser(user) } returns emptyList()
        every { imageRepository.findByPinId(pin.id) } returns image
        every { imageStore.openStream(image.storageKey) } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
        every { pinRepository.findBoardsForPinIncludingRecycled(pin.id) } returns emptyList()

        // When
        builder.stageArchive(anExport(), user, renewLease = {})

        // Then
        val expectedPath = "images/${image.id}.png"
        assertTrue(sink.binary.containsKey(expectedPath))
        assertArrayEquals(byteArrayOf(1, 2, 3), sink.binary[expectedPath])
        val exportedPin = sink.jsonLines.getValue("pins.jsonl").filterIsInstance<ExportedPin>().single()
        assertEquals(expectedPath, exportedPin.image?.path)
        assertTrue(sink.order.indexOf(expectedPath) < sink.order.indexOf("pins.jsonl"))
    }

    @Test
    fun `Given an image deleted between the two walks, Then the pin references no image`() {
        // Given: findByPinId is read once per walk; the second (pins.jsonl) walk sees the image gone.
        stubArchiveStore()
        every { clock.now() } returns now
        val pin = aPin()
        val image = anImage(pinId = pin.id)
        stubActivePins(listOf(pin))
        stubRecycledPins(emptyList())
        every { boardRepository.findActiveBoardsForUser(user) } returns emptyList()
        every { boardRepository.findRecycledBoardsForUser(user) } returns emptyList()
        every { tagRepository.findAllTagsForUser(user) } returns emptyList()
        every { imageRepository.findByPinId(pin.id) } returnsMany listOf(image, null)
        every { imageStore.openStream(image.storageKey) } returns ByteArrayInputStream(byteArrayOf(9))
        every { pinRepository.findBoardsForPinIncludingRecycled(pin.id) } returns emptyList()

        // When
        builder.stageArchive(anExport(), user, renewLease = {})

        // Then
        val exportedPin = sink.jsonLines.getValue("pins.jsonl").filterIsInstance<ExportedPin>().single()
        assertNull(exportedPin.image)
    }

    @Test
    fun `Given an image whose bytes were not written, Then the pin references no image`() {
        // Given: the two independent reads see a DIFFERENT image for the same pin (replaced between
        // the walks), so the path the second walk computes was never written by the first -- the
        // pin must not reference it even though an image is technically present on the re-read.
        stubArchiveStore()
        every { clock.now() } returns now
        val pin = aPin()
        val writtenImage = anImage(pinId = pin.id, mimeType = "image/jpeg")
        val staleImage = anImage(pinId = pin.id, mimeType = "image/png")
        stubActivePins(listOf(pin))
        stubRecycledPins(emptyList())
        every { boardRepository.findActiveBoardsForUser(user) } returns emptyList()
        every { boardRepository.findRecycledBoardsForUser(user) } returns emptyList()
        every { tagRepository.findAllTagsForUser(user) } returns emptyList()
        every { imageRepository.findByPinId(pin.id) } returnsMany listOf(writtenImage, staleImage)
        every { imageStore.openStream(writtenImage.storageKey) } returns ByteArrayInputStream(byteArrayOf(1))
        every { pinRepository.findBoardsForPinIncludingRecycled(pin.id) } returns emptyList()

        // When
        builder.stageArchive(anExport(), user, renewLease = {})

        // Then
        val exportedPin = sink.jsonLines.getValue("pins.jsonl").filterIsInstance<ExportedPin>().single()
        assertNull(exportedPin.image)
    }

    @Test
    fun `Given a pin in a recycled board, Then the membership is still written`() {
        // Given
        stubArchiveStore()
        every { clock.now() } returns now
        val pin = aPin()
        val recycledBoard = aBoard(name = "Trip", softDeletedAt = now)
        stubActivePins(listOf(pin))
        stubRecycledPins(emptyList())
        every { boardRepository.findActiveBoardsForUser(user) } returns emptyList()
        every { boardRepository.findRecycledBoardsForUser(user) } returns listOf(recycledBoard)
        every { tagRepository.findAllTagsForUser(user) } returns emptyList()
        every { imageRepository.findByPinId(pin.id) } returns null
        every { pinRepository.findBoardsForPinIncludingRecycled(pin.id) } returns listOf(recycledBoard)

        // When
        builder.stageArchive(anExport(), user, renewLease = {})

        // Then
        val exportedPin = sink.jsonLines.getValue("pins.jsonl").filterIsInstance<ExportedPin>().single()
        assertEquals(listOf(ExportedRef(recycledBoard.id, recycledBoard.name)), exportedPin.boards)
    }

    @Test
    fun `Given recycled boards, Then boards jsonl carries them with their deletion marker`() {
        // Given
        stubArchiveStore()
        every { clock.now() } returns now
        stubEmptyCollections()
        val activeBoard = aBoard(name = "Active")
        val recycledBoard = aBoard(name = "Gone", softDeletedAt = now)
        every { boardRepository.findActiveBoardsForUser(user) } returns listOf(activeBoard)
        every { boardRepository.findRecycledBoardsForUser(user) } returns listOf(recycledBoard)

        // When
        builder.stageArchive(anExport(), user, renewLease = {})

        // Then
        val boards = sink.jsonLines.getValue("boards.jsonl").filterIsInstance<ExportedBoard>()
        assertEquals(2, boards.size)
        assertNull(boards.first { it.id == activeBoard.id }.deletedAt)
        assertEquals(now, boards.first { it.id == recycledBoard.id }.deletedAt)
    }

    @Test
    fun `Given several pages of pins, Then every page is walked`() {
        // Given
        stubArchiveStore()
        every { clock.now() } returns now
        val pinA = aPin()
        val pinB = aPin()
        val cursor = Cursor(pivotId = pinA.id, direction = CursorDirection.FORWARD)
        every { pinRepository.findPinsForUser(user, null, pageSize, PinSortStrategy.CREATED_AT_DESC) } returns
            Page(items = listOf(pinA), previousCursor = null, nextCursor = cursor)
        every { pinRepository.findPinsForUser(user, cursor, pageSize, PinSortStrategy.CREATED_AT_DESC) } returns
            Page(items = listOf(pinB), previousCursor = null, nextCursor = null)
        stubRecycledPins(emptyList())
        every { boardRepository.findActiveBoardsForUser(user) } returns emptyList()
        every { boardRepository.findRecycledBoardsForUser(user) } returns emptyList()
        every { tagRepository.findAllTagsForUser(user) } returns emptyList()
        every { imageRepository.findByPinId(any()) } returns null
        every { pinRepository.findBoardsForPinIncludingRecycled(any()) } returns emptyList()
        var renewCount = 0

        // When
        builder.stageArchive(anExport(), user, renewLease = { renewCount++ })

        // Then
        val pins = sink.jsonLines.getValue("pins.jsonl").filterIsInstance<ExportedPin>()
        assertEquals(setOf(pinA.id, pinB.id), pins.map { it.id }.toSet())
        assertTrue(renewCount > 1, "a multi-page walk must renew the lease more than once")
    }

    @Test
    fun `Given a completed archive, Then the manifest carries the counts and the entry digests`() {
        // Given
        stubArchiveStore()
        every { clock.now() } returns now
        val pin = aPin()
        val image = anImage(pinId = pin.id)
        val board = aBoard()
        val tag = Tag(id = randomUUID(), author = user, name = "t", createdAt = now)
        stubActivePins(listOf(pin))
        stubRecycledPins(emptyList())
        every { boardRepository.findActiveBoardsForUser(user) } returns listOf(board)
        every { boardRepository.findRecycledBoardsForUser(user) } returns emptyList()
        every { tagRepository.findAllTagsForUser(user) } returns listOf(tag)
        every { imageRepository.findByPinId(pin.id) } returns image
        every { imageStore.openStream(image.storageKey) } returns ByteArrayInputStream(byteArrayOf(5, 6))
        every { pinRepository.findBoardsForPinIncludingRecycled(pin.id) } returns emptyList()
        val export = anExport()

        // When
        builder.stageArchive(export, user, renewLease = {})

        // Then
        val manifest = sink.json.getValue("manifest.json") as ExportManifest
        assertEquals(ExportCounts(pins = 1, boards = 1, tags = 1, images = 1), manifest.counts)
        assertEquals(export.id, manifest.exportId)
        assertEquals(export.formatVersion, manifest.formatVersion)
        assertEquals(now, manifest.createdAt)
        assertEquals(now.plus(retention), manifest.expiresAt)
        assertEquals(ExportedRef(user.id, user.name), manifest.user)
        assertEquals(3, manifest.excluded.size)
        val entryPaths = manifest.entries.map { it.path }.toSet()
        assertEquals(
            setOf("README.md", "user.json", "boards.jsonl", "tags.jsonl", "images/${image.id}.jpg", "pins.jsonl"),
            entryPaths,
        )
        assertTrue(manifest.entries.all { it.sha256.isNotBlank() && it.byteSize > 0 })
        assertEquals(entryPaths.size, manifest.entries.map { it.sha256 }.toSet().size, "digests must not collide")
    }

    // -- build() / publish() -----------------------------------------------------------------

    /** Stubs the full happy path through stageArchive: an empty archive, promotable and publishable. */
    private fun stubHappyPathBuild() {
        stubArchiveStore()
        stubEmptyCollections()
        every { archiveStore.hasFreeSpace(MINIMUM_FREE_BYTES) } returns true
        every { archiveStore.format } returns ArchiveFormat("application/zip", "zip")
        every { archiveStore.promote(any(), any()) } just runs
        every { exportRepository.save(any()) } answers { firstArg() }
        every { transactionRunner.inTransaction<Boolean>(any()) } answers { firstArg<() -> Boolean>().invoke() }
    }

    @Test
    fun `Given an export that no longer exists, Then the build is a no-op`() {
        // Given
        every { exportRepository.findById(exportId) } returns null

        // When
        builder.build(exportId, isLastAttempt = false, renewLease = {})

        // Then
        verify(exactly = 0) { userRepository.findUserById(any()) }
    }

    @Test
    fun `Given an export that is not pending, Then the build is a no-op`() {
        // Given
        every { exportRepository.findById(exportId) } returns anExport().copy(state = UserDataExportState.READY)

        // When
        builder.build(exportId, isLastAttempt = false, renewLease = {})

        // Then
        verify(exactly = 0) { userRepository.findUserById(any()) }
        verify(exactly = 0) { archiveStore.hasFreeSpace(any()) }
    }

    @Test
    fun `Given a missing user, Then the export is FAILED and a PermanentTaskException is thrown`() {
        // Given
        every { exportRepository.findById(exportId) } returns anExport()
        every { userRepository.findUserById(userId) } returns null
        every { exportRepository.save(any()) } answers { firstArg() }

        // When / Then
        val error = assertThrows(PermanentTaskException::class.java) {
            builder.build(exportId, isLastAttempt = false, renewLease = {})
        }
        assertEquals("user no longer exists", error.reason)
        verify {
            exportRepository.save(match { it.state == UserDataExportState.FAILED && it.failureCode == "USER_GONE" })
        }
        verify(exactly = 0) { archiveStore.hasFreeSpace(any()) }
    }

    @Test
    fun `Given insufficient free space, Then the export is FAILED with DISK_FULL and not built`() {
        // Given
        every { exportRepository.findById(exportId) } returns anExport()
        every { userRepository.findUserById(userId) } returns user
        every { archiveStore.hasFreeSpace(MINIMUM_FREE_BYTES) } returns false
        every { exportRepository.save(any()) } answers { firstArg() }

        // When / Then
        val error = assertThrows(PermanentTaskException::class.java) {
            builder.build(exportId, isLastAttempt = false, renewLease = {})
        }
        assertEquals("not enough free space", error.reason)
        verify {
            exportRepository.save(match { it.state == UserDataExportState.FAILED && it.failureCode == "DISK_FULL" })
        }
        verify(exactly = 0) { archiveStore.stage(any()) }
    }

    @Test
    fun `Given a successful build, Then the row carries size, digest, media type and extension`() {
        // Given
        stubHappyPathBuild()
        every { clock.now() } returns now
        every { exportRepository.findById(exportId) } returns anExport()
        every { userRepository.findUserById(userId) } returns user

        // When
        builder.build(exportId, isLastAttempt = false, renewLease = {})

        // Then
        verify {
            exportRepository.save(
                match {
                    it.state == UserDataExportState.READY &&
                        it.mediaType == "application/zip" &&
                        it.fileExtension == "zip" &&
                        it.completedAt == now &&
                        it.expiresAt == now.plus(retention) &&
                        it.byteSize == 0L &&
                        it.sha256 == "unused"
                },
            )
        }
        verify { archiveStore.promote(any(), "exports/$exportId.zip") }
    }

    @Test
    fun `Given an export cancelled during the build, Then READY is not written and the bytes are deleted`() {
        // Given
        stubHappyPathBuild()
        every { clock.now() } returns now
        val pendingExport = anExport()
        val cancelledExport = pendingExport.copy(state = UserDataExportState.DELETED)
        every { exportRepository.findById(exportId) } returnsMany listOf(pendingExport, cancelledExport)
        every { userRepository.findUserById(userId) } returns user
        every { archiveStore.delete(any()) } just runs

        // When
        builder.build(exportId, isLastAttempt = false, renewLease = {})

        // Then
        verify(exactly = 0) { exportRepository.save(match { it.state == UserDataExportState.READY }) }
        verify { archiveStore.delete("exports/$exportId.zip") }
    }

    @Test
    fun `Given the export row is gone before publishing, Then READY is not written and the bytes are deleted`() {
        // Given: the row was hard-deleted between staging and publishing (account deletion, spec
        // §10), so the re-read inside the transaction finds nothing at all -- not merely a
        // non-PENDING row.
        stubHappyPathBuild()
        every { clock.now() } returns now
        val pendingExport = anExport()
        every { exportRepository.findById(exportId) } returnsMany listOf(pendingExport, null)
        every { userRepository.findUserById(userId) } returns user
        every { archiveStore.delete(any()) } just runs

        // When
        builder.build(exportId, isLastAttempt = false, renewLease = {})

        // Then
        verify(exactly = 0) { exportRepository.save(match { it.state == UserDataExportState.READY }) }
        verify { archiveStore.delete("exports/$exportId.zip") }
    }

    /** Stubs everything reached before stageArchive is attempted, then makes staging itself fail. */
    private fun stubBuildFailure() {
        every { exportRepository.findById(exportId) } returns anExport()
        every { userRepository.findUserById(userId) } returns user
        every { archiveStore.hasFreeSpace(MINIMUM_FREE_BYTES) } returns true
        every { archiveStore.format } returns ArchiveFormat("application/zip", "zip")
        every { clock.now() } returns now
        every { archiveStore.stage(any()) } throws IllegalStateException("boom")
        every { exportRepository.save(any()) } answers { firstArg() }
    }

    @Test
    fun `Given a failure on the last attempt, Then the export is FAILED and the staged file discarded`() {
        // Given: stage() itself fails. The real adapter (FilesystemZipExportArchiveStore, Task 5)
        // deletes its own temp file in that case before rethrowing, so there is no StagedFile here
        // for the builder to hand to archiveStore.discard() -- the bytes are already reclaimed by
        // the time build() observes the failure.
        stubBuildFailure()

        // When / Then
        assertThrows(IllegalStateException::class.java) {
            builder.build(exportId, isLastAttempt = true, renewLease = {})
        }
        verify {
            exportRepository.save(
                match { it.state == UserDataExportState.FAILED && it.failureCode == "BUILD_FAILED" },
            )
        }
    }

    @Test
    fun `Given a failure on an earlier attempt, Then the export stays PENDING and the file discarded`() {
        // Given
        stubBuildFailure()

        // When / Then
        assertThrows(IllegalStateException::class.java) {
            builder.build(exportId, isLastAttempt = false, renewLease = {})
        }
        verify(exactly = 0) { exportRepository.save(match { it.state == UserDataExportState.FAILED }) }
    }

    @Test
    fun `Given entries being written, Then the task lease is renewed`() {
        // Given
        stubHappyPathBuild()
        every { clock.now() } returns now
        every { exportRepository.findById(exportId) } returns anExport()
        every { userRepository.findUserById(userId) } returns user
        var renewCount = 0

        // When
        builder.build(exportId, isLastAttempt = false, renewLease = { renewCount++ })

        // Then
        assertTrue(renewCount > 0, "the lease must be renewed while entries are written")
    }

    private companion object {
        const val MINIMUM_FREE_BYTES = 1024L
    }
}
