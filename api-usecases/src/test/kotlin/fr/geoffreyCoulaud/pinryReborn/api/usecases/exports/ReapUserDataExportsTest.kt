package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.Task
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.PassthroughTransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReapUserDataExportsTest : BaseTest() {
    private val repository = mockk<UserDataExportRepositoryInterface>()
    private val archiveStore = mockk<ExportArchiveStore>()
    private val taskQueue = mockk<TaskQueueInterface>()
    private val clock = mockk<Clock>()
    private val transactions = PassthroughTransactionRunner()
    private val now = Instant.parse("2026-07-22T10:00:00Z")
    private val reaper =
        ReapUserDataExports(
            repository, archiveStore, taskQueue, clock, transactions,
            interruptedGrace = INTERRUPTED_GRACE,
            stagedFileMaxAge = STAGED_FILE_MAX_AGE,
            sweepBatchSize = SWEEP_BATCH_SIZE,
        )

    /** The rows as the store holds them, so a refusal is read as the row it left rather than as a call. */
    private val rows = mutableMapOf<UUID, UserDataExport>()
    private val deletedArchives = mutableListOf<String>()
    private var orphanCutoff: Instant? = null
    private val selectionLimits = mutableListOf<Int>()

    /** The key an export's own id derives, which is the one a dead builder's archive is named by. */
    private fun keyOf(exportId: UUID): String = ExportArchiveKey.forExport(exportId, FORMAT.fileExtension)

    private fun anExport(
        state: UserDataExportState,
        id: UUID = randomUUID(),
        storageKey: String? = null,
        requestedAt: Instant = now.minus(Duration.ofDays(10)),
        taskId: UUID? = null,
    ) = UserDataExport(
        id = id, userId = randomUUID(), state = state, formatVersion = 1,
        requestedAt = requestedAt, storageKey = storageKey, taskId = taskId,
    ).also { rows[it.id] = it }

    /** A row naming the bytes its own id derives, which is what a build that completed leaves. */
    private fun exportNamingItsBytes(state: UserDataExportState): UserDataExport {
        val id = randomUUID()
        return anExport(state, id = id, storageKey = keyOf(id))
    }

    private fun aTask(state: TaskState) =
        Task(
            id = randomUUID(), kind = "account.export", payload = "", state = state,
            priority = -1, availableAt = now, attempts = 1, maxAttempts = 3, leaseId = null,
            leaseExpiresAt = null, cancelRequested = false, dedupKey = null, lastError = null,
        )

    private fun stored(id: UUID): UserDataExport = requireNotNull(rows[id])

    /** What every run reads, whether or not it finds anything: the three selections and the tmp sweep. */
    private fun stubSweep() {
        stubSelections()
        every { archiveStore.discardOrphanedStagedFiles(any()) } answers {
            orphanCutoff = firstArg()
            0
        }
    }

    /** Apart from the tmp sweep, so a case can refuse that one without shadowing a stub nothing reaches. */
    private fun stubSelections() {
        every { clock.now() } returns now
        every { repository.findPending(any()) } answers {
            selectionLimits += firstArg<Int>()
            rows.values.filter { row -> row.state == UserDataExportState.PENDING }
        }
        every { repository.findExpiredReadyExports(now) } answers {
            rows.values.filter { row -> row.state == UserDataExportState.READY }
        }
        every { repository.findReclaimableTerminal(any()) } answers {
            selectionLimits += firstArg<Int>()
            rows.values.filter { row -> row.state.isTerminal && row.storageKey != null }
        }
    }

    /** Only the runs that act on a row reach these, and `BaseTest` fails a stub nothing reached. */
    private fun stubRowWrites() {
        every { repository.findById(any()) } answers { rows[firstArg<UUID>()] }
        every { repository.save(any()) } answers { firstArg<UserDataExport>().also { row -> rows[row.id] = row } }
    }

    /** Read only when a reclaim derives a key, so a run acting on no terminal row must not stub it. */
    private fun stubArchiveFormat() {
        every { archiveStore.format } returns FORMAT
    }

    private fun stubArchiveDeletion() {
        stubArchiveFormat()
        every { archiveStore.delete(any()) } answers { deletedArchives += firstArg<String>() }
    }

    /** One key the store refuses and every other taken, which is how one half of a double delete fails. */
    private fun stubArchiveDeletionRefusing(refusedKey: String) {
        stubArchiveFormat()
        every { archiveStore.delete(any()) } answers {
            val key = firstArg<String>()
            if (key == refusedKey) throw IOException("permission denied")
            deletedArchives += key
        }
    }

    /**
     * The racing actor committing between the batch selection and this row's write, which only a read
     * inside the write's transaction sees: answered outside it, the fence would pass unfenced code.
     */
    private fun stubRacedRow(raced: UserDataExport, state: UserDataExportState) {
        every { repository.findById(raced.id) } answers {
            if (!transactions.inside) return@answers stored(raced.id)
            stored(raced.id).copy(state = state).also { row -> rows[row.id] = row }
        }
    }

    // --- Pass 1: a build nothing is driving any more ---

    @Test
    fun `Given a pending export whose task is dead, Then it fails as interrupted`() {
        // Given: claimNext moves a task to DEAD inline once its attempts are spent
        stubSweep()
        stubRowWrites()
        val task = aTask(TaskState.DEAD)
        val stuck = anExport(UserDataExportState.PENDING, taskId = task.id)
        every { taskQueue.findById(task.id) } returns task

        // When
        val counts = reaper.reap()

        // Then
        assertEquals(ExportSweepCounts(failed = 1, expired = 0, reclaimed = 0), counts)
        assertEquals(UserDataExportState.FAILED, stored(stuck.id).state)
        assertEquals("EXPORT_INTERRUPTED", stored(stuck.id).failureCode)
    }

    @Test
    fun `Given a pending export whose task the queue no longer holds, Then it fails as interrupted`() {
        // Given: the terminal task sweep deletes a task past its grace, so absent means reaped. It never
        // means "not enqueued yet": the task and the row that names it commit together.
        stubSweep()
        stubRowWrites()
        val taskId = randomUUID()
        val stuck = anExport(UserDataExportState.PENDING, taskId = taskId)
        every { taskQueue.findById(taskId) } returns null

        // When
        val counts = reaper.reap()

        // Then
        assertEquals(ExportSweepCounts(failed = 1, expired = 0, reclaimed = 0), counts)
        assertEquals(UserDataExportState.FAILED, stored(stuck.id).state)
    }

    @Test
    fun `Given a pending export naming no task at all, Then it fails as interrupted`() {
        // Given: the column is nullable because the row exists before its task does
        stubSweep()
        stubRowWrites()
        val stuck = anExport(UserDataExportState.PENDING, taskId = null)

        // When
        val counts = reaper.reap()

        // Then
        assertEquals(ExportSweepCounts(failed = 1, expired = 0, reclaimed = 0), counts)
        assertEquals(UserDataExportState.FAILED, stored(stuck.id).state)
    }

    @Test
    fun `Given a pending export whose task succeeded, Then it fails as interrupted`() {
        // Given: a handler that returned without publishing leaves exactly this, and a predicate
        // spelled "dead or absent" reads the task as one still coming back to the row.
        stubSweep()
        stubRowWrites()
        val task = aTask(TaskState.SUCCEEDED)
        val stuck = anExport(UserDataExportState.PENDING, taskId = task.id)
        every { taskQueue.findById(task.id) } returns task

        // When
        val counts = reaper.reap()

        // Then
        assertEquals(ExportSweepCounts(failed = 1, expired = 0, reclaimed = 0), counts)
        assertEquals(UserDataExportState.FAILED, stored(stuck.id).state)
    }

    @Test
    fun `Given a pending export whose task was cancelled, Then it fails as interrupted`() {
        // Given: the other settled state a "dead or absent" predicate would take for a live attempt
        stubSweep()
        stubRowWrites()
        val task = aTask(TaskState.CANCELLED)
        val stuck = anExport(UserDataExportState.PENDING, taskId = task.id)
        every { taskQueue.findById(task.id) } returns task

        // When
        val counts = reaper.reap()

        // Then
        assertEquals(ExportSweepCounts(failed = 1, expired = 0, reclaimed = 0), counts)
        assertEquals(UserDataExportState.FAILED, stored(stuck.id).state)
    }

    @Test
    fun `Given a pending export whose task is still live, Then the build is left alone`() {
        // Given: a lease that expired goes back to PENDING rather than DEAD, so both are a live attempt
        stubSweep()
        val claimed = aTask(TaskState.RUNNING)
        val requeued = aTask(TaskState.PENDING)
        anExport(UserDataExportState.PENDING, taskId = claimed.id)
        anExport(UserDataExportState.PENDING, taskId = requeued.id)
        every { taskQueue.findById(claimed.id) } returns claimed
        every { taskQueue.findById(requeued.id) } returns requeued

        // When
        val counts = reaper.reap()

        // Then
        assertEquals(ExportSweepCounts(failed = 0, expired = 0, reclaimed = 0), counts)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `Given a pending export younger than the grace, Then the build is left alone`() {
        // Given: a DEAD task does not mean no builder is running, and an attempt lasts as long as its
        // staging progresses. Condemning here writes FAILED under a builder holding a complete archive.
        stubSweep()
        val task = aTask(TaskState.DEAD)
        anExport(UserDataExportState.PENDING, requestedAt = now.minus(Duration.ofHours(1)), taskId = task.id)

        // When
        val counts = reaper.reap()

        // Then: the grace is read before the queue, so a young row costs no lookup either
        assertEquals(ExportSweepCounts(failed = 0, expired = 0, reclaimed = 0), counts)
        verify(exactly = 0) { taskQueue.findById(any()) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `Given a pending export whose age has just reached the grace, Then the build is left alone`() {
        // Given: the boundary the grace is read on, equality included. Section 4.3 makes the asymmetry
        // deliberate, so a build reaching the grace this instant is still one holding an archive.
        stubSweep()
        anExport(UserDataExportState.PENDING, requestedAt = now.minus(INTERRUPTED_GRACE), taskId = null)

        // When
        val counts = reaper.reap()

        // Then: the fence is never opened, which is where a row condemned at equality would go
        assertEquals(ExportSweepCounts(failed = 0, expired = 0, reclaimed = 0), counts)
        verify(exactly = 0) { repository.findById(any()) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `Given an interrupted build naming its bytes, Then one run fails it and reclaims them`() {
        // Given: the builder stamps its key before it stages, so a row nothing is driving any more
        // almost always names a file. This is the ordinary shape of a pass 1 row, not the bare one.
        stubSweep()
        stubRowWrites()
        stubArchiveDeletion()
        val stuck = exportNamingItsBytes(UserDataExportState.PENDING)

        // When
        val counts = reaper.reap()

        // Then: pass 1 makes it terminal and pass 3 takes the bytes in that same passage
        assertEquals(ExportSweepCounts(failed = 1, expired = 0, reclaimed = 1), counts)
        assertEquals(UserDataExportState.FAILED, stored(stuck.id).state)
        assertEquals(listOf(keyOf(stuck.id)), deletedArchives)
        assertNull(stored(stuck.id).storageKey)
    }

    @Test
    fun `Given a pending export deleted while the sweep read it, Then no failure is written over it`() {
        // Given: the owner's DELETE lands between the selection and the fence, and a FAILED written
        // over it would lose the reason the row is gone
        stubSweep()
        val task = aTask(TaskState.DEAD)
        val raced = anExport(UserDataExportState.PENDING, taskId = task.id)
        every { taskQueue.findById(task.id) } returns task
        stubRacedRow(raced, UserDataExportState.DELETED)

        // When
        val counts = reaper.reap()

        // Then
        assertEquals(ExportSweepCounts(failed = 0, expired = 0, reclaimed = 0), counts)
        assertEquals(UserDataExportState.DELETED, stored(raced.id).state)
        verify(exactly = 0) { repository.save(any()) }
    }

    // --- Pass 2: the expiry writes the state, and pass 3 takes the bytes ---

    @Test
    fun `Given a ready export past its expiry, Then it becomes EXPIRED and its bytes go in the same run`() {
        // Given: the expiry writes the state and nothing else; every terminal row's bytes are pass 3's
        stubSweep()
        stubRowWrites()
        stubArchiveDeletion()
        val export = exportNamingItsBytes(UserDataExportState.READY)

        // When
        val counts = reaper.reap()

        // Then: one row, counted by both passes it met
        assertEquals(ExportSweepCounts(failed = 0, expired = 1, reclaimed = 1), counts)
        assertEquals(UserDataExportState.EXPIRED, stored(export.id).state)
        assertEquals(listOf(keyOf(export.id)), deletedArchives)
        assertNull(stored(export.id).storageKey)
    }

    @Test
    fun `Given an expired export naming no bytes, Then nothing is reclaimed after it`() {
        // Given: pass 3 selects on the column, so a row that never named bytes leaves it nothing to do
        stubSweep()
        stubRowWrites()
        val export = anExport(UserDataExportState.READY, storageKey = null)

        // When
        val counts = reaper.reap()

        // Then
        assertEquals(ExportSweepCounts(failed = 0, expired = 1, reclaimed = 0), counts)
        assertEquals(UserDataExportState.EXPIRED, stored(export.id).state)
        verify(exactly = 0) { archiveStore.delete(any()) }
    }

    @Test
    fun `Given nothing to sweep, Then nothing is written and every count is zero`() {
        // Given
        stubSweep()

        // When
        val counts = reaper.reap()

        // Then
        assertEquals(ExportSweepCounts(failed = 0, expired = 0, reclaimed = 0), counts)
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { archiveStore.delete(any()) }
    }

    @Test
    fun `Given an export that moved on while the sweep read it, Then nothing is written over it`() {
        // Given: the owner's DELETE and a new request's SUPERSEDED are the two met in practice.
        // Ranged over every state, a single-state refusal telling state == READY from no looser one.
        stubSweep()
        val racedStates = UserDataExportState.entries.filter { it != UserDataExportState.READY }
        assertTrue(racedStates.isNotEmpty())

        racedStates.forEach { state ->
            rows.clear()
            val raced = anExport(UserDataExportState.READY, storageKey = null)
            stubRacedRow(raced, state)

            // When
            val counts = reaper.reap()

            // Then
            assertEquals(ExportSweepCounts(failed = 0, expired = 0, reclaimed = 0), counts)
            assertEquals(state, stored(raced.id).state)
        }
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { archiveStore.delete(any()) }
    }

    @Test
    fun `Given two expired exports and one of them raced, Then only the row moved is counted`() {
        // Given: a fully refused sweep and a fully successful one would otherwise return the same number
        stubSweep()
        stubRowWrites()
        val raced = anExport(UserDataExportState.READY, storageKey = null)
        val swept = anExport(UserDataExportState.READY, storageKey = null)
        stubRacedRow(raced, UserDataExportState.DELETED)

        // When
        val counts = reaper.reap()

        // Then
        assertEquals(ExportSweepCounts(failed = 0, expired = 1, reclaimed = 0), counts)
        assertEquals(UserDataExportState.EXPIRED, stored(swept.id).state)
        assertEquals(UserDataExportState.DELETED, stored(raced.id).state)
    }

    @Test
    fun `Given one export the database refuses, Then the rest of the run still happens`() {
        // Given: item-level isolation, as ReapUserDataImports has for the same reason
        stubSweep()
        stubRowWrites()
        val refused = anExport(UserDataExportState.READY, storageKey = null)
        val swept = anExport(UserDataExportState.READY, storageKey = null)
        every { repository.save(match { it.id == refused.id }) } throws RuntimeException("db down")

        // When
        val counts = reaper.reap()

        // Then
        assertEquals(ExportSweepCounts(failed = 0, expired = 1, reclaimed = 0), counts)
        assertEquals(UserDataExportState.EXPIRED, stored(swept.id).state)
        assertEquals(UserDataExportState.READY, stored(refused.id).state)
    }

    // --- Pass 3: a terminal row stops naming bytes only once they are gone ---

    @Test
    fun `Given a terminal export naming its own bytes, Then they go once and only once`() {
        // Given: the stamp is what keeps the same bytes from being deleted every sweep
        stubSweep()
        stubRowWrites()
        stubArchiveDeletion()
        val superseded = exportNamingItsBytes(UserDataExportState.SUPERSEDED)

        // When
        val first = reaper.reap()
        val second = reaper.reap()

        // Then
        assertEquals(ExportSweepCounts(failed = 0, expired = 0, reclaimed = 1), first)
        assertEquals(ExportSweepCounts(failed = 0, expired = 0, reclaimed = 0), second)
        assertEquals(listOf(keyOf(superseded.id)), deletedArchives)
        assertNull(stored(superseded.id).storageKey)
    }

    @Test
    fun `Given a terminal export whose column names another key, Then both keys go`() {
        // Given: deleting only the derived key succeeds vacuously here, the column is then cleared, and
        // the bytes it named become unreachable to every sweep. That is the defect of section 2.4.
        stubSweep()
        stubRowWrites()
        stubArchiveDeletion()
        val divergent = anExport(UserDataExportState.SUPERSEDED, storageKey = DIVERGENT_KEY)

        // When
        val counts = reaper.reap()

        // Then
        assertEquals(ExportSweepCounts(failed = 0, expired = 0, reclaimed = 1), counts)
        assertEquals(listOf(keyOf(divergent.id), DIVERGENT_KEY), deletedArchives)
        assertNull(stored(divergent.id).storageKey)
    }

    @Test
    fun `Given a terminal export whose column's bytes will not go, Then the row keeps naming them`() {
        // Given: the derived key goes and the column's refuses. A second delete made quiet would report
        // the row reclaimed and clear the column, stranding real bytes as section 2.4's defect did.
        stubSweep()
        val divergent = anExport(UserDataExportState.SUPERSEDED, storageKey = DIVERGENT_KEY)
        stubArchiveDeletionRefusing(DIVERGENT_KEY)

        // When
        val counts = reaper.reap()

        // Then
        assertEquals(ExportSweepCounts(failed = 0, expired = 0, reclaimed = 0), counts)
        assertEquals(listOf(keyOf(divergent.id)), deletedArchives)
        assertEquals(DIVERGENT_KEY, stored(divergent.id).storageKey)
        // The fence is never opened, which is the half a swallowed refusal would reach anyway
        verify(exactly = 0) { repository.findById(any()) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `Given a terminal export whose derived bytes will not go, Then the column keeps naming its own`() {
        // Given: the mirror. Quieting this half hides the archive a dead builder left on the derived
        // key, which is the residue the derivation exists to name.
        stubSweep()
        val divergent = anExport(UserDataExportState.SUPERSEDED, storageKey = DIVERGENT_KEY)
        stubArchiveDeletionRefusing(keyOf(divergent.id))

        // When
        val counts = reaper.reap()

        // Then: the derived key goes first, so the column's is not reached this run either
        assertEquals(ExportSweepCounts(failed = 0, expired = 0, reclaimed = 0), counts)
        assertEquals(emptyList<String>(), deletedArchives)
        assertEquals(DIVERGENT_KEY, stored(divergent.id).storageKey)
        verify(exactly = 0) { repository.findById(any()) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `Given two terminal exports and one whose bytes will not go, Then the other is still reclaimed`() {
        // Given: a try/catch around the loop rather than around the row would abandon the rest of the
        // batch, which is the isolation this class promises.
        stubSweep()
        stubRowWrites()
        val refused = exportNamingItsBytes(UserDataExportState.SUPERSEDED)
        val swept = exportNamingItsBytes(UserDataExportState.EXPIRED)
        stubArchiveDeletionRefusing(keyOf(refused.id))

        // When
        val counts = reaper.reap()

        // Then
        assertEquals(ExportSweepCounts(failed = 0, expired = 0, reclaimed = 1), counts)
        assertEquals(listOf(keyOf(swept.id)), deletedArchives)
        assertEquals(keyOf(refused.id), stored(refused.id).storageKey)
        assertNull(stored(swept.id).storageKey)
    }

    @Test
    fun `Given a store that will not take an archive back, Then the row keeps naming those bytes`() {
        // Given: stamping over a failed delete would hide the residue from the only sweep that names it
        stubSweep()
        stubArchiveFormat()
        val stuck = exportNamingItsBytes(UserDataExportState.DELETED)
        every { archiveStore.delete(any()) } throws IOException("permission denied")

        // When
        val counts = reaper.reap()

        // Then: not counted either, so the next run retries it rather than reporting it done
        assertEquals(ExportSweepCounts(failed = 0, expired = 0, reclaimed = 0), counts)
        assertEquals(keyOf(stuck.id), stored(stuck.id).storageKey)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `Given an export erased while its archive was reclaimed, Then only the bytes go`() {
        // Given: the account deletion cleaner drops the row between the selection and the stamp, so
        // there is nothing left to stamp and the bytes it named are already named by nothing else
        stubSweep()
        stubArchiveDeletion()
        val erased = exportNamingItsBytes(UserDataExportState.FAILED)
        every { repository.findById(erased.id) } returns null

        // When
        val counts = reaper.reap()

        // Then
        assertEquals(ExportSweepCounts(failed = 0, expired = 0, reclaimed = 0), counts)
        assertEquals(listOf(keyOf(erased.id)), deletedArchives)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `Given a terminal export read as pending at the stamp, Then its key is kept`() {
        // Given: nothing moves a terminal row back today, and the fence is what keeps the stamp from
        // depending on that. Refused, it must leave the column naming the bytes it just tried to free.
        stubSweep()
        stubArchiveDeletion()
        val raced = exportNamingItsBytes(UserDataExportState.EXPIRED)
        stubRacedRow(raced, UserDataExportState.PENDING)

        // When
        val counts = reaper.reap()

        // Then
        assertEquals(ExportSweepCounts(failed = 0, expired = 0, reclaimed = 0), counts)
        assertEquals(keyOf(raced.id), stored(raced.id).storageKey)
        verify(exactly = 0) { repository.save(any()) }
    }

    // --- The run as a whole ---

    @Test
    fun `Given one row per pass, Then each count reports its own pass`() {
        // Given: one Int would answer three questions at once, and the operator reads the three
        stubSweep()
        stubRowWrites()
        stubArchiveDeletion()
        val interrupted = anExport(UserDataExportState.PENDING, taskId = null)
        val expiring = anExport(UserDataExportState.READY, storageKey = null)
        val reclaimable = exportNamingItsBytes(UserDataExportState.FAILED)

        // When
        val counts = reaper.reap()

        // Then
        assertEquals(ExportSweepCounts(failed = 1, expired = 1, reclaimed = 1), counts)
        assertEquals(UserDataExportState.FAILED, stored(interrupted.id).state)
        assertEquals(UserDataExportState.EXPIRED, stored(expiring.id).state)
        assertNull(stored(reclaimable.id).storageKey)
    }

    @Test
    fun `Given any run, Then both bounded selections take the configured batch size`() {
        // Given: an unbounded select the caller truncates still materialises the whole history, which
        // is what the first run after deployment would do over every terminal export ever written
        stubSweep()

        // When
        reaper.reap()

        // Then
        assertEquals(listOf(SWEEP_BATCH_SIZE, SWEEP_BATCH_SIZE), selectionLimits)
    }

    @Test
    fun `Given any run, Then staged files past their age are discarded`() {
        // Given: bytes whose export row never existed, which no row-driven path can see
        stubSweep()

        // When
        reaper.reap()

        // Then
        assertEquals(now.minus(STAGED_FILE_MAX_AGE), orphanCutoff)
    }

    @Test
    fun `Given a staging sweep the store refuses, Then the run still reports what its passes moved`() {
        // Given: the tmp walk runs once the three passes have written their rows, so a failure there
        // costs the counts they earned, and the sole scenario the startup guard exists for logs nothing
        stubSelections()
        stubRowWrites()
        stubArchiveDeletion()
        exportNamingItsBytes(UserDataExportState.READY)
        every { archiveStore.discardOrphanedStagedFiles(any()) } throws IOException("permission denied")

        // When
        val counts = reaper.reap()

        // Then
        assertEquals(ExportSweepCounts(failed = 0, expired = 1, reclaimed = 1), counts)
    }

    private companion object {
        // Deliberately apart, where production gives both the same PT6H: equal here, the two would be
        // interchangeable and a sweep reading one for the other would pass every case in this file.
        private val INTERRUPTED_GRACE: Duration = Duration.ofHours(6)
        private val STAGED_FILE_MAX_AGE: Duration = Duration.ofHours(2)
        private const val SWEEP_BATCH_SIZE = 500
        private val FORMAT = ArchiveFormat("application/zip", "zip")

        /** A column naming bytes the derivation does not, which is what makes the double delete two. */
        private const val DIVERGENT_KEY = "exports/divergent.txt"
    }
}
