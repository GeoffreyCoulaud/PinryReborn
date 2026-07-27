package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportAlreadyInProgressException
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.Task
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.Reauthenticator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ExportAlreadyInProgressError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ExportTooSoonError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ReauthenticationError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.EnqueueTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.UserDataExportTask
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID.randomUUID

class UserDataExportRequesterTest : BaseTest() {
    private val repository = mockk<UserDataExportRepositoryInterface>()
    private val archiveStore = mockk<ExportArchiveStore>()
    private val enqueueTask = mockk<EnqueueTask>()
    private val reauthenticator = mockk<Reauthenticator>()
    private val clock = mockk<Clock>()
    private val transactionRunner = mockk<TransactionRunner>()
    private val now = Instant.parse("2026-07-22T10:00:00Z")
    private val user = User(id = randomUUID(), name = "alice", createdAt = Instant.now())
    private val factor = "good-password"
    private val requester =
        UserDataExportRequester(
            repository, archiveStore, enqueueTask, reauthenticator, clock, transactionRunner,
            minimumInterval = Duration.ofHours(1),
        )

    // Runs inside a test only when that test's execution actually reaches the transactional block;
    // BaseTest.checkUnnecessaryStub() fails a test that declares a stub it never exercises, so the
    // early-throwing tests (before the transaction) must not call this.
    private fun stubTransactionPassthrough() {
        every { transactionRunner.inTransaction<Pair<UserDataExport, String?>>(any()) } answers
            { firstArg<() -> Pair<UserDataExport, String?>>().invoke() }
    }

    private fun pendingExport(requestedAt: Instant = now) =
        UserDataExport(
            id = randomUUID(), userId = user.id, state = UserDataExportState.PENDING,
            formatVersion = 1, requestedAt = requestedAt,
        )

    private fun readyExport(storageKey: String?) =
        UserDataExport(
            id = randomUUID(), userId = user.id, state = UserDataExportState.READY,
            formatVersion = 1, requestedAt = now.minus(Duration.ofDays(2)), storageKey = storageKey,
        )

    private fun aTask() =
        Task(
            id = randomUUID(), kind = UserDataExportTask.KIND, payload = "p", state = TaskState.PENDING,
            priority = 0, availableAt = now, attempts = 0, maxAttempts = UserDataExportTask.MAX_ATTEMPTS,
            leaseId = null, leaseExpiresAt = null, cancelRequested = false, dedupKey = null, lastError = null,
        )

    private fun stubEnqueue(task: Task = aTask()): Task {
        every {
            enqueueTask.enqueue(
                kind = UserDataExportTask.KIND,
                payload = any(),
                maxAttempts = UserDataExportTask.MAX_ATTEMPTS,
            )
        } returns task
        return task
    }

    @Test
    fun `Given a wrong step-up factor, Then nothing is written and no task is enqueued`() {
        // Given
        every { reauthenticator.reauthenticate(user, factor) } throws ReauthenticationError()

        // When / Then
        assertThrows(ReauthenticationError::class.java) { requester.request(user, factor) }
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { enqueueTask.enqueue(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Given no previous export, Then a pending export is created and a task enqueued`() {
        // Given
        stubTransactionPassthrough()
        every { reauthenticator.reauthenticate(user, factor) } just runs
        every { clock.now() } returns now
        every { repository.findPendingForUser(user.id) } returns null
        every { repository.findLastRequestedAtForUser(user.id) } returns null
        every { repository.findReadyForUser(user.id) } returns null
        every { repository.save(any()) } answers { firstArg() }
        val task = stubEnqueue()

        // When
        val result = requester.request(user, factor)

        // Then
        assertEquals(UserDataExportState.PENDING, result.state)
        assertEquals(user.id, result.userId)
        assertEquals(task.id, result.taskId)
        verify(exactly = 2) { repository.save(any()) }
        verify {
            enqueueTask.enqueue(
                kind = UserDataExportTask.KIND,
                payload = result.id.toString(),
                maxAttempts = UserDataExportTask.MAX_ATTEMPTS,
            )
        }
    }

    @Test
    fun `Given a pending export, Then requesting again throws ExportAlreadyInProgressError`() {
        // Given
        stubTransactionPassthrough()
        every { reauthenticator.reauthenticate(user, factor) } just runs
        every { clock.now() } returns now
        every { repository.findPendingForUser(user.id) } returns pendingExport()

        // When / Then
        assertThrows(ExportAlreadyInProgressError::class.java) { requester.request(user, factor) }
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { enqueueTask.enqueue(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Given a request within the minimum interval, Then it throws ExportTooSoonError`() {
        // Given
        stubTransactionPassthrough()
        every { reauthenticator.reauthenticate(user, factor) } just runs
        every { clock.now() } returns now
        every { repository.findPendingForUser(user.id) } returns null
        every { repository.findLastRequestedAtForUser(user.id) } returns now.minus(Duration.ofMinutes(30))

        // When / Then
        val error = assertThrows(ExportTooSoonError::class.java) { requester.request(user, factor) }
        assertEquals(Duration.ofMinutes(30).seconds, error.retryAfterSeconds)
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { enqueueTask.enqueue(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Given a previous export older than the minimum interval, Then a new export is created`() {
        // Given
        stubTransactionPassthrough()
        every { reauthenticator.reauthenticate(user, factor) } just runs
        every { clock.now() } returns now
        every { repository.findPendingForUser(user.id) } returns null
        every { repository.findLastRequestedAtForUser(user.id) } returns now.minus(Duration.ofHours(2))
        every { repository.findReadyForUser(user.id) } returns null
        every { repository.save(any()) } answers { firstArg() }
        stubEnqueue()

        // When
        val result = requester.request(user, factor)

        // Then
        assertEquals(UserDataExportState.PENDING, result.state)
        verify(exactly = 2) { repository.save(any()) }
    }

    @Test
    fun `Given a ready export, Then it is superseded and its bytes deleted after the commit`() {
        // Given
        stubTransactionPassthrough()
        every { reauthenticator.reauthenticate(user, factor) } just runs
        every { clock.now() } returns now
        every { repository.findPendingForUser(user.id) } returns null
        every { repository.findLastRequestedAtForUser(user.id) } returns null
        val ready = readyExport(storageKey = "exports/old.zip")
        every { repository.findReadyForUser(user.id) } returns ready
        every { repository.save(any()) } answers { firstArg() }
        stubEnqueue()
        every { archiveStore.delete("exports/old.zip") } just runs

        // When
        requester.request(user, factor)

        // Then
        verify {
            repository.save(
                match { it.id == ready.id && it.state == UserDataExportState.SUPERSEDED && it.storageKey == null },
            )
        }
        verify { archiveStore.delete("exports/old.zip") }
    }

    @Test
    fun `Given a ready export without a storage key, Then no delete is attempted`() {
        // Given
        stubTransactionPassthrough()
        every { reauthenticator.reauthenticate(user, factor) } just runs
        every { clock.now() } returns now
        every { repository.findPendingForUser(user.id) } returns null
        every { repository.findLastRequestedAtForUser(user.id) } returns null
        val ready = readyExport(storageKey = null)
        every { repository.findReadyForUser(user.id) } returns ready
        every { repository.save(any()) } answers { firstArg() }
        stubEnqueue()

        // When
        requester.request(user, factor)

        // Then
        verify(exactly = 0) { archiveStore.delete(any()) }
    }

    @Test
    fun `Given the transaction never runs, Then nothing is written`() {
        // Given: the transaction runner never invokes its block, so the write path never executes.
        every { reauthenticator.reauthenticate(user, factor) } just runs
        val canned: Pair<UserDataExport, String?> = pendingExport() to null
        every { transactionRunner.inTransaction<Pair<UserDataExport, String?>>(any()) } returns canned

        // When
        val result = requester.request(user, factor)

        // Then
        assertEquals(canned.first, result)
        verify(exactly = 0) { repository.findPendingForUser(any()) }
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { enqueueTask.enqueue(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { archiveStore.delete(any()) }
    }

    @Test
    fun `Given the pending export save loses the race, Then it throws ExportAlreadyInProgressError`() {
        // Given: findPendingForUser missed the race (checked just before a concurrent request's
        // insert), so the partial unique index is what actually catches the second PENDING row.
        stubTransactionPassthrough()
        every { reauthenticator.reauthenticate(user, factor) } just runs
        every { clock.now() } returns now
        every { repository.findPendingForUser(user.id) } returns null
        every { repository.findLastRequestedAtForUser(user.id) } returns null
        every { repository.findReadyForUser(user.id) } returns null
        val domainException = ExportAlreadyInProgressException()
        every { repository.save(match { it.state == UserDataExportState.PENDING }) } throws domainException

        // When
        val error = assertThrows(ExportAlreadyInProgressError::class.java) { requester.request(user, factor) }

        // Then
        assertEquals(domainException, error.cause)
        verify(exactly = 0) { enqueueTask.enqueue(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Given the superseded archive delete throws, Then the request still succeeds`() {
        // Given: the transaction has committed (the new PENDING row is saved and its task enqueued),
        // so deleting the previous READY archive is post-success cleanup. A disk failure here must
        // not 500 a request that already succeeded.
        stubTransactionPassthrough()
        every { reauthenticator.reauthenticate(user, factor) } just runs
        every { clock.now() } returns now
        every { repository.findPendingForUser(user.id) } returns null
        every { repository.findLastRequestedAtForUser(user.id) } returns null
        val ready = readyExport(storageKey = "exports/old.zip")
        every { repository.findReadyForUser(user.id) } returns ready
        every { repository.save(any()) } answers { firstArg() }
        stubEnqueue()
        every { archiveStore.delete("exports/old.zip") } throws RuntimeException("io")

        // When
        val result = requester.request(user, factor)

        // Then
        assertEquals(UserDataExportState.PENDING, result.state)
        verify { archiveStore.delete("exports/old.zip") }
    }
}
