package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.Task
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportNotAwaitingArchiveError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportPermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.EnqueueTask
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class UserDataImportArchiveCompleterTest : BaseTest() {
    private val repository = mockk<UserDataImportRepositoryInterface>()
    private val archiveStore = mockk<ImportArchiveStore>()
    private val enqueueTask = mockk<EnqueueTask>()
    private val clock = mockk<Clock>()
    private val completer = UserDataImportArchiveCompleter(repository, archiveStore, enqueueTask, clock)
    private val user = User(id = randomUUID(), name = "alice", createdAt = TestTime.now)
    private val stranger = User(id = randomUUID(), name = "mallory", createdAt = TestTime.now)
    private val importId = randomUUID()
    private val now = Instant.parse("2026-08-14T10:00:00Z")
    private val storageKey = "imports/$importId.zip"
    private val staged = StagedFile(path = "/data/tmp/import-$importId.part", byteSize = 4096, contentHash = "h")

    private fun importWith(state: UserDataImportState = UserDataImportState.AWAITING_ARCHIVE) =
        UserDataImport(id = importId, userId = user.id, state = state, requestedAt = now, uploadedBytes = 4096)

    private fun aTask() =
        Task(
            id = randomUUID(), kind = "account.import", payload = importId.toString(), state = TaskState.PENDING,
            priority = -1, availableAt = now, attempts = 0, maxAttempts = 5, leaseId = null,
            leaseExpiresAt = null, cancelRequested = false, dedupKey = null, lastError = null,
        )

    private fun stubCompletion(): Task {
        every { archiveStore.finishUpload(importId) } returns staged
        every { archiveStore.promote(staged, storageKey) } just runs
        every { repository.save(any()) } answers { firstArg() }
        every { clock.now() } returns now
        val task = aTask()
        every {
            enqueueTask.enqueue(
                kind = "account.import",
                payload = importId.toString(),
                maxAttempts = 5,
                priority = -1,
            )
        } returns task
        return task
    }

    @Test
    fun `Given an unknown import, Then completion is refused as absent`() {
        // Given
        every { repository.findById(importId) } returns null

        // When / Then
        assertThrows(ImportDoesNotExistError::class.java) { completer.complete(user, importId) }
        verify(exactly = 0) { archiveStore.finishUpload(any()) }
    }

    @Test
    fun `Given another user's import, Then completion is refused before its state is read`() {
        // Given
        every { repository.findById(importId) } returns importWith(state = UserDataImportState.RUNNING)

        // When / Then
        assertThrows(ImportPermissionError::class.java) { completer.complete(stranger, importId) }
        verify(exactly = 0) { archiveStore.finishUpload(any()) }
    }

    @Test
    fun `Given an import past its upload phase, Then completion is refused`() {
        // Given
        every { repository.findById(importId) } returns importWith(state = UserDataImportState.PENDING)

        // When / Then
        assertThrows(ImportNotAwaitingArchiveError::class.java) { completer.complete(user, importId) }
        verify(exactly = 0) { archiveStore.finishUpload(any()) }
    }

    @Test
    fun `Given a finished upload, Then the storage key is written before the bytes are promoted`() {
        // Given
        every { repository.findById(importId) } returns importWith()
        stubCompletion()

        // When
        completer.complete(user, importId)

        // Then: bytes named before they exist, so a completer that dies leaves them reclaimable
        verifyOrder {
            repository.save(match { it.storageKey == storageKey && it.byteSize == staged.byteSize })
            archiveStore.promote(staged, storageKey)
        }
    }

    @Test
    fun `Given a finished upload, Then the import moves to PENDING and carries its task`() {
        // Given
        every { repository.findById(importId) } returns importWith()
        val task = stubCompletion()

        // When
        val completed = completer.complete(user, importId)

        // Then
        assertEquals(UserDataImportState.PENDING, completed.state)
        assertEquals(now, completed.archiveCompletedAt)
        assertEquals(storageKey, completed.storageKey)
        assertEquals(staged.byteSize, completed.byteSize)
        assertEquals(task.id, completed.taskId)
    }

    @Test
    fun `Given a finished upload, Then the task is enqueued below every other kind and is retried five times`() {
        // Given: the literals rather than the constants, so a change to either has to be meant
        every { repository.findById(importId) } returns importWith()
        stubCompletion()

        // When
        completer.complete(user, importId)

        // Then
        verify {
            enqueueTask.enqueue(
                kind = "account.import",
                payload = importId.toString(),
                maxAttempts = 5,
                priority = -1,
            )
        }
    }

    @Test
    fun `Given a finished upload, Then the task is enqueued only once the row is PENDING`() {
        // Given: a worker claiming the task first would find a row still awaiting its archive, return,
        // and leave the import to be swept rather than run.
        every { repository.findById(importId) } returns importWith()
        stubCompletion()

        // When
        completer.complete(user, importId)

        // Then
        verifyOrder {
            repository.save(match { it.state == UserDataImportState.PENDING })
            enqueueTask.enqueue(kind = any(), payload = any(), maxAttempts = any(), priority = any())
        }
    }
}
