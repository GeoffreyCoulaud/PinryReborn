package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.Task
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID

class ReapAbandonedUserDataImportsTest : BaseTest() {
    private val repository = mockk<UserDataImportRepositoryInterface>()
    private val archiveStore = mockk<ImportArchiveStore>()
    private val taskQueue = mockk<TaskQueueInterface>()
    private val clock = mockk<Clock>()
    private val transactions = PassthroughTransactionRunner()

    private val sweep =
        ReapAbandonedUserDataImports(
            repository, archiveStore, taskQueue, clock, transactions,
            uploadGrace = UPLOAD_GRACE,
            stagedFileMaxAge = STAGED_FILE_MAX_AGE,
        )

    private val now: Instant = Instant.parse("2026-08-15T10:00:00Z")
    private val userId: UUID = randomUUID()

    /** The rows as the store holds them, so a fenced write reads what the write before it left. */
    private val rows = mutableMapOf<UUID, UserDataImport>()
    private val discardedUploads = mutableListOf<UUID>()
    private val deletedArchives = mutableListOf<String>()
    private var abandonCutoff: Instant? = null
    private var orphanCutoff: Instant? = null

    private fun anImport(
        state: UserDataImportState,
        storageKey: String? = null,
        taskId: UUID? = null,
    ) = UserDataImport(
        id = randomUUID(),
        userId = userId,
        state = state,
        requestedAt = now.minus(Duration.ofDays(2)),
        storageKey = storageKey,
        taskId = taskId,
    ).also { rows[it.id] = it }

    private fun aTask(state: TaskState) =
        Task(
            id = randomUUID(), kind = "account.import", payload = "", state = state,
            priority = -1, availableAt = now, attempts = 1, maxAttempts = 5, leaseId = null,
            leaseExpiresAt = null, cancelRequested = false, dedupKey = null, lastError = null,
        )

    /** What every run reads, whether or not it finds anything: the three selections and the tmp sweep. */
    private fun stubSweep() {
        every { clock.now() } returns now
        every { repository.findAbandonableBefore(any()) } answers {
            abandonCutoff = firstArg()
            rows.values.filter { row -> row.state == UserDataImportState.AWAITING_ARCHIVE }
        }
        every { repository.findRunning() } answers {
            rows.values.filter { row -> row.state == UserDataImportState.RUNNING }
        }
        every { repository.findReclaimableTerminal() } answers {
            rows.values.filter { row -> row.state.isTerminal && row.storageKey != null }
        }
        every { archiveStore.discardOrphanedStagedFiles(any()) } answers { orphanCutoff = firstArg(); 0 }
    }

    /** Only the runs that act on a row reach these, and `BaseTest` fails a stub nothing reached. */
    private fun stubRowWrites() {
        every { repository.findById(any()) } answers { rows[firstArg<UUID>()] }
        every { repository.save(any()) } answers {
            firstArg<UserDataImport>().also { row -> rows[row.id] = row }
        }
    }

    private fun stubUploadDiscard() {
        every { archiveStore.discardPartialUpload(any()) } answers { discardedUploads += firstArg<UUID>() }
    }

    private fun stubArchiveDeletion() {
        every { archiveStore.delete(any()) } answers { deletedArchives += firstArg<String>() }
    }

    private fun stored(id: UUID): UserDataImport = requireNotNull(rows[id])

    @Test
    fun `Given an upload past its grace, Then it is abandoned and its partial upload discarded`() {
        // Given
        stubSweep()
        stubRowWrites()
        stubUploadDiscard()
        val stale = anImport(UserDataImportState.AWAITING_ARCHIVE)

        // When
        val reaped = sweep.reap()

        // Then: the grace counts back from now, which is what makes it inactivity rather than age
        assertEquals(1, reaped)
        assertEquals(UserDataImportState.ABANDONED, stored(stale.id).state)
        assertEquals(listOf(stale.id), discardedUploads)
        assertEquals(now.minus(UPLOAD_GRACE), abandonCutoff)
    }

    @Test
    fun `Given an upload completed while the sweep read it, Then its file is left where it is`() {
        // Given: the completer promotes and moves the row on between the selection and the write, and
        // unlinking the upload then would pull a promoted archive's source out from under it
        stubSweep()
        val racing = anImport(UserDataImportState.AWAITING_ARCHIVE)
        every { repository.findById(racing.id) } answers {
            stored(racing.id).copy(state = UserDataImportState.PENDING)
        }

        // When
        val reaped = sweep.reap()

        // Then
        assertEquals(0, reaped)
        verify(exactly = 0) { archiveStore.discardPartialUpload(any()) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `Given a hand-over that never landed, Then the row is abandoned and its bytes reclaimed in one run`() {
        // Given: the completer promoted its archive, then failed before the hand-over committed, so the
        // row still awaits an archive it already has. This is what one transaction over the hand-over
        // leaves behind, and the two paths have to close it between them.
        stubSweep()
        stubRowWrites()
        stubUploadDiscard()
        stubArchiveDeletion()
        val promoted = anImport(UserDataImportState.AWAITING_ARCHIVE)
        rows[promoted.id] = stored(promoted.id).copy(storageKey = ImportArchiveKey.forImport(promoted.id))

        // When
        val reaped = sweep.reap()

        // Then
        assertEquals(2, reaped)
        assertEquals(UserDataImportState.ABANDONED, stored(promoted.id).state)
        assertEquals(listOf(ImportArchiveKey.forImport(promoted.id)), deletedArchives)
        assertNull(stored(promoted.id).storageKey)
    }

    @Test
    fun `Given a terminal import still holding an archive, Then its bytes go once and only once`() {
        // Given: the stamp is what keeps the same bytes from being deleted every hour
        stubSweep()
        stubRowWrites()
        stubArchiveDeletion()
        val cancelled =
            anImport(UserDataImportState.CANCELLED, storageKey = "imports/reclaimable.zip")

        // When
        val first = sweep.reap()
        val second = sweep.reap()

        // Then: keyed on the derived key, so bytes a dead completer promoted are named too
        assertEquals(1, first)
        assertEquals(0, second)
        assertEquals(listOf(ImportArchiveKey.forImport(cancelled.id)), deletedArchives)
        assertNull(stored(cancelled.id).storageKey)
    }

    @Test
    fun `Given a store that will not take an archive back, Then the row keeps naming those bytes`() {
        // Given: stamping over a failed delete would hide the residue from the only sweep that names it
        stubSweep()
        val cancelled = anImport(UserDataImportState.CANCELLED, storageKey = "imports/stuck.zip")
        every { archiveStore.delete(any()) } throws IOException("permission denied")

        // When
        val reaped = sweep.reap()

        // Then
        assertEquals(0, reaped)
        assertEquals("imports/stuck.zip", stored(cancelled.id).storageKey)
    }

    @Test
    fun `Given a running import whose task is dead, Then it fails as interrupted`() {
        // Given
        stubSweep()
        stubRowWrites()
        val task = aTask(TaskState.DEAD)
        val running = anImport(UserDataImportState.RUNNING, taskId = task.id)
        every { taskQueue.findById(task.id) } returns task

        // When
        val reaped = sweep.reap()

        // Then
        assertEquals(1, reaped)
        assertEquals(UserDataImportState.FAILED, stored(running.id).state)
        assertEquals("IMPORT_INTERRUPTED", stored(running.id).failureCode)
    }

    @Test
    fun `Given a running import whose task the queue no longer holds, Then it fails as interrupted`() {
        // Given: the terminal task sweep deletes a task past its grace, so absent means reaped. It never
        // means "not enqueued yet": the task and the row that names it commit together.
        stubSweep()
        stubRowWrites()
        val taskId = randomUUID()
        val running = anImport(UserDataImportState.RUNNING, taskId = taskId)
        every { taskQueue.findById(taskId) } returns null

        // When
        val reaped = sweep.reap()

        // Then
        assertEquals(1, reaped)
        assertEquals(UserDataImportState.FAILED, stored(running.id).state)
    }

    @Test
    fun `Given a running import naming no task at all, Then it fails as interrupted`() {
        // Given: the column is nullable because the row exists before its task does, and a run nothing
        // drives will not advance whatever the reason its id is missing
        stubSweep()
        stubRowWrites()
        val running = anImport(UserDataImportState.RUNNING, taskId = null)

        // When
        val reaped = sweep.reap()

        // Then
        assertEquals(1, reaped)
        assertEquals(UserDataImportState.FAILED, stored(running.id).state)
    }

    @Test
    fun `Given a running import whose task ended, Then it fails as interrupted`() {
        // Given: a live attempt is a task PENDING or RUNNING (spec section 6). One that succeeded or was
        // cancelled is not coming back to this row, whatever left the row RUNNING behind it.
        stubSweep()
        stubRowWrites()
        val succeeded = aTask(TaskState.SUCCEEDED)
        val cancelled = aTask(TaskState.CANCELLED)
        val afterSuccess = anImport(UserDataImportState.RUNNING, taskId = succeeded.id)
        val afterCancellation = anImport(UserDataImportState.RUNNING, taskId = cancelled.id)
        every { taskQueue.findById(succeeded.id) } returns succeeded
        every { taskQueue.findById(cancelled.id) } returns cancelled

        // When
        val reaped = sweep.reap()

        // Then
        assertEquals(2, reaped)
        assertEquals(UserDataImportState.FAILED, stored(afterSuccess.id).state)
        assertEquals(UserDataImportState.FAILED, stored(afterCancellation.id).state)
    }

    @Test
    fun `Given a walk that finished while the sweep read it, Then no failure is written over it`() {
        // Given: the runner writes COMPLETED between the selection and the fence, and the task it has
        // just finished is on its way out of the queue
        stubSweep()
        val task = aTask(TaskState.DEAD)
        val running = anImport(UserDataImportState.RUNNING, taskId = task.id)
        every { taskQueue.findById(task.id) } returns task
        every { repository.findById(running.id) } answers {
            stored(running.id).copy(state = UserDataImportState.COMPLETED)
        }

        // When
        val reaped = sweep.reap()

        // Then
        assertEquals(0, reaped)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `Given an import erased while its archive was reclaimed, Then only the bytes go`() {
        // Given: the account deletion cleaner drops the row between the selection and the stamp, so
        // there is nothing left to stamp and the bytes it named are already gone
        stubSweep()
        stubArchiveDeletion()
        val erased = anImport(UserDataImportState.CANCELLED, storageKey = "imports/erased.zip")
        every { repository.findById(erased.id) } returns null

        // When
        val reaped = sweep.reap()

        // Then
        assertEquals(0, reaped)
        assertEquals(listOf(ImportArchiveKey.forImport(erased.id)), deletedArchives)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `Given a running import whose task is live, Then the walk is left alone`() {
        // Given: a lease that expired goes back to PENDING rather than DEAD, so both are a live attempt
        stubSweep()
        val claimed = aTask(TaskState.RUNNING)
        val requeued = aTask(TaskState.PENDING)
        anImport(UserDataImportState.RUNNING, taskId = claimed.id)
        anImport(UserDataImportState.RUNNING, taskId = requeued.id)
        every { taskQueue.findById(claimed.id) } returns claimed
        every { taskQueue.findById(requeued.id) } returns requeued

        // When
        val reaped = sweep.reap()

        // Then
        assertEquals(0, reaped)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `Given a row whose sweep fails outside Exception, Then the failure is not absorbed`() {
        // Given: the isolation absorbs an Exception deliberately, and nothing wider. detekt reads catch
        // clauses only, so a swallow broader than Exception would ship with no suppression and no reason.
        // Stubbed by hand rather than through stubSweep(): the run stops here, so the two selections
        // after it and the staged-file sweep are never reached.
        every { clock.now() } returns now
        stubRowWrites()
        val refused = anImport(UserDataImportState.AWAITING_ARCHIVE)
        every { repository.findAbandonableBefore(any()) } returns listOf(refused)
        every { archiveStore.discardPartialUpload(refused.id) } throws StackOverflowError("native frame")

        // When / Then
        assertThrows(StackOverflowError::class.java) { sweep.reap() }
    }

    @Test
    fun `Given any run, Then staged files past their age are discarded`() {
        // Given: an upload whose import row never existed, which no row-driven path can see
        stubSweep()

        // When
        sweep.reap()

        // Then
        assertEquals(now.minus(STAGED_FILE_MAX_AGE), orphanCutoff)
    }

    @Test
    fun `Given one row the store refuses, Then the rest of the run still happens`() {
        // Given: item-level isolation, as ReapExpiredUserDataExports has for the same reason
        stubSweep()
        stubRowWrites()
        val refused = anImport(UserDataImportState.AWAITING_ARCHIVE)
        val swept = anImport(UserDataImportState.AWAITING_ARCHIVE)
        every { archiveStore.discardPartialUpload(refused.id) } throws IOException("permission denied")
        every { archiveStore.discardPartialUpload(swept.id) } answers { discardedUploads += swept.id }

        // When
        val reaped = sweep.reap()

        // Then
        assertEquals(1, reaped)
        assertEquals(listOf(swept.id), discardedUploads)
        assertEquals(UserDataImportState.ABANDONED, stored(swept.id).state)
    }

    private companion object {
        private val UPLOAD_GRACE: Duration = Duration.ofHours(24)
        private val STAGED_FILE_MAX_AGE: Duration = Duration.ofHours(48)
    }
}
