package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveTooLargeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportChunkOffsetMismatchException
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportArchiveTooLargeError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportChunkOffsetMismatchError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportInsufficientStorageError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportNotAwaitingArchiveError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportPermissionError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID.randomUUID

class UserDataImportChunkReceiverTest : BaseTest() {
    private val repository = mockk<UserDataImportRepositoryInterface>()
    private val archiveStore = mockk<ImportArchiveStore>()
    private val clock = mockk<Clock>()
    private val maxArchiveBytes = 1_000L
    private val minimumFreeBytes = 64L
    private val receiver =
        UserDataImportChunkReceiver(repository, archiveStore, clock, maxArchiveBytes, minimumFreeBytes)
    private val user = User(id = randomUUID(), name = "alice", createdAt = TestTime.now)
    private val stranger = User(id = randomUUID(), name = "mallory", createdAt = TestTime.now)
    private val importId = randomUUID()
    private val now = Instant.parse("2026-08-14T10:00:00Z")
    private val chunk = byteArrayOf(1, 2, 3, 4)

    private fun importWith(
        state: UserDataImportState = UserDataImportState.AWAITING_ARCHIVE,
        uploadedBytes: Long = 0,
    ) = UserDataImport(
        id = importId, userId = user.id, state = state, requestedAt = now, uploadedBytes = uploadedBytes,
    )

    private fun receive(
        asUser: User = user,
        offset: Long = 0,
    ) = receiver.receive(asUser, importId, offset, ByteArrayInputStream(chunk))

    @Test
    fun `Given an unknown import, Then the chunk is refused as absent`() {
        // Given
        every { repository.findById(importId) } returns null

        // When / Then
        assertThrows(ImportDoesNotExistError::class.java) { receive() }
        verify(exactly = 0) { archiveStore.appendChunk(any(), any(), any(), any()) }
    }

    @Test
    fun `Given another user's import, Then the chunk is refused before its state is read`() {
        // Given: owner before state, so a stranger cannot tell an awaiting import from a running one
        every { repository.findById(importId) } returns importWith(state = UserDataImportState.RUNNING)

        // When / Then
        assertThrows(ImportPermissionError::class.java) { receive(asUser = stranger) }
        verify(exactly = 0) { archiveStore.appendChunk(any(), any(), any(), any()) }
    }

    @Test
    fun `Given an import past its upload phase, Then the chunk is refused`() {
        // Given
        every { repository.findById(importId) } returns importWith(state = UserDataImportState.PENDING)

        // When / Then
        assertThrows(ImportNotAwaitingArchiveError::class.java) { receive() }
        verify(exactly = 0) { archiveStore.appendChunk(any(), any(), any(), any()) }
    }

    @Test
    fun `Given free space below the margin, Then nothing is appended and the row is untouched`() {
        // Given
        every { repository.findById(importId) } returns importWith(uploadedBytes = 512)
        every { archiveStore.hasFreeSpace(minimumFreeBytes) } returns false

        // When / Then: the client resumes from 512 rather than restarting
        assertThrows(ImportInsufficientStorageError::class.java) { receive(offset = 512) }
        verify(exactly = 0) { archiveStore.appendChunk(any(), any(), any(), any()) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `Given a chunk at the current length, Then it is appended and the row stamped`() {
        // Given
        every { repository.findById(importId) } returns importWith(uploadedBytes = 512)
        every { archiveStore.hasFreeSpace(minimumFreeBytes) } returns true
        every { archiveStore.appendChunk(importId, 512, any(), maxArchiveBytes) } returns 516
        every { clock.now() } returns now
        every { repository.save(any()) } answers { firstArg() }

        // When
        val updated = receive(offset = 512)

        // Then
        assertEquals(516, updated.uploadedBytes)
        assertEquals(now, updated.lastUploadActivityAt)
        assertEquals(UserDataImportState.AWAITING_ARCHIVE, updated.state)
    }

    @Test
    fun `Given an out-of-order offset, Then the refusal carries the current length`() {
        // Given
        every { repository.findById(importId) } returns importWith(uploadedBytes = 512)
        every { archiveStore.hasFreeSpace(minimumFreeBytes) } returns true
        every { archiveStore.appendChunk(importId, 0, any(), maxArchiveBytes) } throws
            ImportChunkOffsetMismatchException(currentLength = 512)

        // When
        val error = assertThrows(ImportChunkOffsetMismatchError::class.java) { receive(offset = 0) }

        // Then: read from disk, which is the authority when the row and the bytes have drifted apart
        assertEquals(512, error.currentLength)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `Given a chunk carrying the archive past its maximum, Then it is refused and nothing is stamped`() {
        // Given
        every { repository.findById(importId) } returns importWith(uploadedBytes = 998)
        every { archiveStore.hasFreeSpace(minimumFreeBytes) } returns true
        val refusal = ImportArchiveTooLargeException(maxTotalBytes = maxArchiveBytes)
        every { archiveStore.appendChunk(importId, 998, any(), maxArchiveBytes) } throws refusal

        // When
        val error = assertThrows(ImportArchiveTooLargeError::class.java) { receive(offset = 998) }

        // Then
        assertEquals(refusal, error.cause)
        verify(exactly = 0) { repository.save(any()) }
    }
}
