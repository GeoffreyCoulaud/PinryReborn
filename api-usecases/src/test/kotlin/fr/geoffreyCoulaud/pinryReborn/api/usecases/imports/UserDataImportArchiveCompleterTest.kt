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
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportArchiveEmptyError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportNotAwaitingArchiveError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportPermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.EnqueueTask
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.NoSuchFileException
import java.time.Instant
import java.util.UUID.randomUUID

class UserDataImportArchiveCompleterTest : BaseTest() {
    private val repository = mockk<UserDataImportRepositoryInterface>()
    private val archiveStore = mockk<ImportArchiveStore>()
    private val enqueueTask = mockk<EnqueueTask>()
    private val clock = mockk<Clock>()
    private val transactions = PassthroughTransactionRunner()
    private val completer =
        UserDataImportArchiveCompleter(repository, archiveStore, enqueueTask, clock, transactions)
    private val user = User(id = randomUUID(), name = "alice", createdAt = TestTime.now)
    private val stranger = User(id = randomUUID(), name = "mallory", createdAt = TestTime.now)
    private val importId = randomUUID()
    private val now = Instant.parse("2026-08-14T10:00:00Z")
    private val storageKey = "imports/$importId.zip"
    private val staged = StagedFile(path = "/data/tmp/import-$importId.part", byteSize = 4096, contentHash = "h")

    /** The row as the store holds it: a fenced write reads what the write before it left, not a copy. */
    private var row = importWith()

    /** How a re-read inside a fence answers, null included. Cases replace it rather than restubbing. */
    private var reread: (UserDataImport) -> UserDataImport? = { it }

    private var digested = false
    private var promoted = false

    /** Which transaction each write and the enqueue saw open, so two sequential ones never read as one. */
    private val savedInTransactions = mutableListOf<Int?>()
    private var enqueuedInTransaction: Int? = null
    private val deletedArchives = mutableListOf<String>()

    private fun importWith(state: UserDataImportState = UserDataImportState.AWAITING_ARCHIVE) =
        UserDataImport(id = importId, userId = user.id, state = state, requestedAt = now, uploadedBytes = 4096)

    private fun aTask() =
        Task(
            id = randomUUID(), kind = "account.import", payload = importId.toString(), state = TaskState.PENDING,
            priority = -1, availableAt = now, attempts = 0, maxAttempts = 5, leaseId = null,
            leaseExpiresAt = null, cancelRequested = false, dedupKey = null, lastError = null,
        )

    /** Reads answer the stored row, so every fence sees what the write before it committed. */
    private fun stubStoredRow(stored: UserDataImport = importWith()) {
        row = stored
        every { repository.findById(importId) } answers { reread(row) }
    }

    /**
     * Another actor landing at a chosen point of the completion: from then on a re-read answers [state],
     * which is how a concurrent request reaches this use case, by the row rather than by a call.
     */
    private fun landing(state: UserDataImportState, landed: () -> Boolean) {
        reread = { current ->
            when {
                landed() -> current.copy(state = state)
                else -> current
            }
        }
    }

    /** How a `DELETE` reaches this use case. */
    private fun cancelWhen(landed: () -> Boolean) = landing(UserDataImportState.CANCELLED, landed)

    private fun stubDigest() {
        every { archiveStore.finishUpload(importId) } answers { digested = true; staged }
    }

    private fun stubPromote() {
        every { archiveStore.promote(staged, storageKey) } answers { promoted = true }
    }

    private fun stubRowWrites() {
        every { repository.save(any()) } answers {
            savedInTransactions += transactions.current
            firstArg<UserDataImport>().also { saved -> row = saved }
        }
    }

    private fun stubArchiveDeletion() {
        every { archiveStore.delete(any()) } answers { deletedArchives += firstArg<String>() }
    }

    private fun stubEnqueue(): Task =
        aTask().also { task ->
            every {
                enqueueTask.enqueue(
                    kind = "account.import",
                    payload = importId.toString(),
                    maxAttempts = 5,
                    priority = -1,
                )
            } answers {
                enqueuedInTransaction = transactions.current
                task
            }
        }

    /** Everything a completion that is never interrupted needs. */
    private fun stubCompletion(): Task {
        stubStoredRow()
        stubDigest()
        stubPromote()
        stubRowWrites()
        every { clock.now() } returns now
        return stubEnqueue()
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
    fun `Given an import that received no chunk, Then completion is refused before the store is touched`() {
        // Given: nothing created the upload file, so `finishUpload` opens an absent path and raises an
        // untyped NoSuchFileException, a 500 for the client. Its trace is in this commit's message.
        every { repository.findById(importId) } returns importWith().copy(uploadedBytes = 0)

        // When / Then
        assertThrows(ImportArchiveEmptyError::class.java) { completer.complete(user, importId) }
        verify(exactly = 0) { archiveStore.finishUpload(any()) }
    }

    @Test
    fun `Given an upload unlinked before it is closed, Then the completion is refused, not failed`() {
        // Given: the canceller unlinks the partial upload before it writes CANCELLED, so the file can be
        // gone while the row still reads AWAITING_ARCHIVE, and the store answers with an untyped throw
        stubStoredRow()
        every { archiveStore.finishUpload(importId) } throws NoSuchFileException(staged.path)

        // When / Then: what the next GET shows, not a 500
        assertThrows(ImportNotAwaitingArchiveError::class.java) { completer.complete(user, importId) }
    }

    @Test
    fun `Given an upload unlinked before it is promoted, Then the completion is refused, not failed`() {
        // Given: the same window one step later, between the fence and the atomic move
        stubStoredRow()
        stubDigest()
        stubRowWrites()
        every { archiveStore.promote(staged, storageKey) } throws NoSuchFileException(staged.path)

        // When / Then: nothing was promoted, so nothing is deleted on the way out
        assertThrows(ImportNotAwaitingArchiveError::class.java) { completer.complete(user, importId) }
        verify(exactly = 0) { archiveStore.delete(any()) }
    }

    @Test
    fun `Given a finished upload, Then the storage key is written before the bytes are promoted`() {
        // Given
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
        stubCompletion()

        // When
        completer.complete(user, importId)

        // Then
        verifyOrder {
            repository.save(match { it.state == UserDataImportState.PENDING })
            enqueueTask.enqueue(kind = any(), payload = any(), maxAttempts = any(), priority = any())
        }
    }

    @Test
    fun `Given a finished upload, Then the transition, the task and the row naming it commit together`() {
        // Given: a PENDING row whose enqueue never landed holds the account's only active slot, keeps
        // its bytes, and is rescued by no sweep: the three writes are one transaction or none
        stubCompletion()

        // When
        completer.complete(user, importId)

        // Then: the same open transaction at the transition, at the enqueue and at the task id write
        val handOver = savedInTransactions.last()
        assertNotNull(handOver)
        assertEquals(listOf(handOver, handOver), savedInTransactions.takeLast(2))
        assertEquals(handOver, enqueuedInTransaction)
    }

    @Test
    fun `Given an account erased while the archive was digested, Then the completion is refused`() {
        // Given: the cleaner deletes the import rows, so the fence finds no row at all rather than one
        // in another state, and the bytes it promoted answer to nobody
        stubStoredRow()
        stubDigest()
        stubPromote()
        stubRowWrites()
        reread = { current -> if (promoted) null else current }
        stubArchiveDeletion()

        // When / Then
        assertThrows(ImportNotAwaitingArchiveError::class.java) { completer.complete(user, importId) }
        assertEquals(listOf(storageKey), deletedArchives)
    }

    @Test
    fun `Given a second completion that won the race, Then this one is refused and its bytes go`() {
        // Given: the concurrent complete left the row PENDING, which passes a not-terminal fence and
        // fails the awaiting-archive one; a second walk over the same archive must not be enqueued
        stubStoredRow()
        stubDigest()
        stubPromote()
        stubRowWrites()
        landing(UserDataImportState.PENDING) { promoted }
        stubArchiveDeletion()

        // When / Then
        assertThrows(ImportNotAwaitingArchiveError::class.java) { completer.complete(user, importId) }
        assertEquals(listOf(storageKey), deletedArchives)
        verify(exactly = 0) { enqueueTask.enqueue(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Given a cancellation landing while the archive is digested, Then nothing is promoted`() {
        // Given: the DELETE lands during the fsync and digest of up to twenty gigabytes, discarding the
        // partial upload the promote would then move
        stubStoredRow()
        stubDigest()
        cancelWhen { digested }

        // When / Then
        assertThrows(ImportNotAwaitingArchiveError::class.java) { completer.complete(user, importId) }
        verify(exactly = 0) { archiveStore.promote(any(), any()) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `Given a cancellation landing while the bytes are promoted, Then they are deleted and no task is enqueued`() {
        // Given: an unfenced save would write PENDING over the CANCELLED the user was told about, and
        // enqueue a walk for it
        stubStoredRow()
        stubDigest()
        stubPromote()
        stubRowWrites()
        cancelWhen { promoted }
        stubArchiveDeletion()

        // When / Then
        assertThrows(ImportNotAwaitingArchiveError::class.java) { completer.complete(user, importId) }
        assertEquals(listOf(storageKey), deletedArchives)
        verify(exactly = 0) { enqueueTask.enqueue(any(), any(), any(), any(), any(), any()) }
    }
}
