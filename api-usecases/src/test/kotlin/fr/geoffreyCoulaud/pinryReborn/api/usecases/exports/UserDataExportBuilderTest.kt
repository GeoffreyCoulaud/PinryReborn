package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.CursorDirection
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.exceptions.PermanentTaskException
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.util.UUID.randomUUID
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class UserDataExportBuilderTest : UserDataExportBuilderFixtures() {

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
        // the walks), so the path the second walk computes was never written by the first.
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

    // -- build(): the entry guard ---------------------------------------------------------------

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
        stubRow(anExport().copy(state = UserDataExportState.READY))

        // When
        builder.build(exportId, isLastAttempt = false, renewLease = {})

        // Then
        verify(exactly = 0) { userRepository.findUserById(any()) }
        verify(exactly = 0) { archiveStore.hasFreeSpace(any()) }
    }

    @Test
    fun `Given a missing user, Then the export is FAILED and a PermanentTaskException is thrown`() {
        // Given
        stubRow()
        stubRowWrites()
        every { userRepository.findUserById(userId) } returns null

        // When / Then
        val error = assertThrows(PermanentTaskException::class.java) {
            builder.build(exportId, isLastAttempt = false, renewLease = {})
        }
        assertEquals("user no longer exists", error.reason)
        assertEquals(UserDataExportState.FAILED, stored()?.state)
        assertEquals("USER_GONE", stored()?.failureCode)
        verify(exactly = 0) { archiveStore.hasFreeSpace(any()) }
    }

    @Test
    fun `Given insufficient free space, Then the export is FAILED with DISK_FULL and not built`() {
        // Given
        stubRow()
        stubRowWrites()
        every { userRepository.findUserById(userId) } returns user
        every { archiveStore.hasFreeSpace(minimumFreeBytes) } returns false

        // When / Then
        val error = assertThrows(PermanentTaskException::class.java) {
            builder.build(exportId, isLastAttempt = false, renewLease = {})
        }
        assertEquals("not enough free space", error.reason)
        assertEquals(UserDataExportState.FAILED, stored()?.state)
        assertEquals("DISK_FULL", stored()?.failureCode)
        verify(exactly = 0) { archiveStore.stage(any()) }
    }

    // -- build(): the two fenced writes and the publish that follows them -----------------------

    @Test
    fun `Given a successful build, Then the row carries size, digest, media type and extension`() {
        // Given: over the fake store, so the archive is read as what the disk holds afterwards
        stubFakeStoreBuild()

        // When
        fakeStoreBuilder.build(exportId, isLastAttempt = false, renewLease = {})

        // Then
        val published = requireNotNull(stored())
        assertEquals(UserDataExportState.READY, published.state)
        assertEquals(storageKey, published.storageKey)
        assertEquals(stagedByteSize, published.byteSize)
        assertEquals(stagedHash, published.sha256)
        assertEquals("application/zip", published.mediaType)
        assertEquals("zip", published.fileExtension)
        assertEquals(now, published.completedAt)
        assertEquals(now.plus(retention), published.expiresAt)
        assertEquals(listOf(storageKey), fakeArchiveStore.promoted.keys.toList())
        assertEquals(stagedHash, fakeArchiveStore.promoted.getValue(storageKey).contentHash)
    }

    @Test
    fun `Given a key being stamped, Then the row is read and written in one transaction`() {
        // Given: the predicate alone holds against two successive transactions, and a DELETE landing
        // between them is restored by merge, which writes every column of the copy it is handed. The
        // single connection serialises each statement, not a pair (`docs/adr/0016`, decision 1).
        stubBuildToStaging()
        stubFailingStage()

        // When: the staging fails on an attempt that is not the last, so the stamp is the only write
        assertThrows(IllegalStateException::class.java) {
            builder.build(exportId, isLastAttempt = false, renewLease = {})
        }

        // Then: the entry read is outside any transaction, and the stamp reads in the one it writes in
        val fenced = writtenInTransactions.single()
        assertNotNull(fenced, "the stamp should write inside a transaction")
        assertEquals(listOf(null, fenced), readInTransactions)
    }

    @Test
    fun `Given an export that moved on before its key was stamped, Then the row keeps the state it moved to`() {
        // Given: the write commits between the read that started the build and the read the stamp
        // takes, so only a read inside that write's transaction sees it. Ranged over every state the
        // window can commit, a single-state refusal telling state == PENDING from no looser predicate.
        stubBuildEntry()
        val racedStates = UserDataExportState.entries.filter { it != UserDataExportState.PENDING }
        assertTrue(racedStates.isNotEmpty())

        racedStates.forEach { state ->
            seedRow(anExport())
            reread = { row -> if (transactions.inside) row.copy(state = state).also(::seedRow) else row }

            // When: no task failure is raised either, so the attempt settles as a success
            builder.build(exportId, isLastAttempt = false, renewLease = {})

            // Then
            assertEquals(state, stored()?.state)
        }
        verify(exactly = 0) { archiveStore.stage(any()) }
        verify(exactly = 0) { exportRepository.save(any()) }
    }

    @Test
    fun `Given an export erased before its key was stamped, Then no row is written back into existence`() {
        // Given: the row goes while the stamp reads it, and merge is an upsert, so a fence testing the
        // copy read first would write it back into existence. The window is the helper's own, not one
        // an actor opens: only the account cleaner deletes an export row, and it runs on an account
        // `findUserById` already hides, so `requireUser` throws first. The case below is that one.
        stubBuildEntry()
        eraseWhen { transactions.inside }

        // When
        builder.build(exportId, isLastAttempt = false, renewLease = {})

        // Then
        assertNull(stored())
        verify(exactly = 0) { archiveStore.stage(any()) }
        verify(exactly = 0) { exportRepository.save(any()) }
    }

    @Test
    fun `Given an account erased while the build read it, Then no row is written back into existence`() {
        // Given: the erasure the cleaner does produce, both rows dropped in one transaction, so the
        // user lookup answers nothing and the failure marking finds no row to write over.
        stubRow()
        var userRead = false
        every { userRepository.findUserById(userId) } answers {
            userRead = true
            null
        }
        eraseWhen { userRead }

        // When / Then: the queue still gets the permanent failure, and nothing is re-inserted for it
        val error = assertThrows(PermanentTaskException::class.java) {
            builder.build(exportId, isLastAttempt = false, renewLease = {})
        }
        assertEquals("user no longer exists", error.reason)
        assertNull(stored())
        verify(exactly = 0) { exportRepository.save(any()) }
    }

    @Test
    fun `Given an export cancelled during the build, Then READY is not written and the bytes are deleted`() {
        // Given: the DELETE lands after the archive is staged, which is the window publish fences
        stubHappyPathBuild()
        deleteWhen { stageCalls > 0 }
        every { archiveStore.delete(any()) } just runs

        // When
        builder.build(exportId, isLastAttempt = false, renewLease = {})

        // Then
        assertEquals(UserDataExportState.DELETED, stored()?.state)
        verify { archiveStore.delete(storageKey) }
    }

    @Test
    fun `Given the export row is gone before publishing, Then READY is not written and the bytes are deleted`() {
        // Given: the row was hard-deleted between staging and publishing (account deletion), so the
        // re-read inside the transaction finds nothing at all, not merely a non-PENDING row.
        stubHappyPathBuild()
        eraseWhen { stageCalls > 0 }
        every { archiveStore.delete(any()) } just runs

        // When
        builder.build(exportId, isLastAttempt = false, renewLease = {})

        // Then
        assertNull(stored())
        verify { archiveStore.delete(storageKey) }
    }

    @Test
    fun `Given an export deleted while the build failed, Then FAILED is not written over DELETED`() {
        // Given: the DELETE lands while the archive is being written, which is site 2's window, and
        // FAILED over it would turn isGone back to false on a row the user was told was gone.
        stubBuildToStaging()
        stubFailingStage()
        deleteWhen { stageCalls > 0 }

        // When / Then: the staging error still reaches the queue untouched
        assertThrows(IllegalStateException::class.java) {
            builder.build(exportId, isLastAttempt = true, renewLease = {})
        }
        assertEquals(UserDataExportState.DELETED, stored()?.state)
        assertNull(stored()?.failureCode)
    }

    @Test
    fun `Given a failure on the last attempt, Then the export is FAILED with BUILD_FAILED`() {
        // Given: stage() itself fails, and the real adapter has already reclaimed its own temp file
        stubBuildToStaging()
        stubFailingStage()

        // When / Then
        assertThrows(IllegalStateException::class.java) {
            builder.build(exportId, isLastAttempt = true, renewLease = {})
        }
        assertEquals(UserDataExportState.FAILED, stored()?.state)
        assertEquals("BUILD_FAILED", stored()?.failureCode)
    }

    @Test
    fun `Given a build that stamped its key before it failed, Then the FAILED row still names those bytes`() {
        // Given: the row read at the build's entry names no key, and the stamp writes one after it, so
        // a failure marked from that first copy would erase the only name the residue has.
        stubBuildToStaging()
        stubFailingStage()

        // When / Then
        assertThrows(IllegalStateException::class.java) {
            builder.build(exportId, isLastAttempt = true, renewLease = {})
        }
        assertEquals(storageKey, stored()?.storageKey)
    }

    @Test
    fun `Given a failure on an earlier attempt, Then the export stays PENDING`() {
        // Given
        stubBuildToStaging()
        stubFailingStage()

        // When / Then
        assertThrows(IllegalStateException::class.java) {
            builder.build(exportId, isLastAttempt = false, renewLease = {})
        }
        assertEquals(UserDataExportState.PENDING, stored()?.state)
    }

    @Test
    fun `Given entries being written, Then the task lease is renewed`() {
        // Given
        stubHappyPathBuild()
        var renewCount = 0

        // When
        builder.build(exportId, isLastAttempt = false, renewLease = { renewCount++ })

        // Then
        assertTrue(renewCount > 0, "the lease must be renewed while entries are written")
    }
}
