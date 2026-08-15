package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ClaimedTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ExponentialBackoffWithJitter
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.NewTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.TaskModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.EbeanTaskQueue
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class EbeanTaskQueueTest : RepositoryTest() {
    // The real policy at full jitter, so the delay a reap writes is exactly computable rather than
    // stubbed: at one attempt the window is the base itself.
    private val backoffPolicy = ExponentialBackoffWithJitter(base = BACKOFF_BASE, cap = BACKOFF_CAP) { 1.0 }
    private val queue = EbeanTaskQueue(persistor, transactionRunner, backoffPolicy)
    private val now = Instant.parse("2026-07-08T00:00:00Z")

    private fun newTask(
        kind: String = "test.kind",
        dedupKey: String? = null,
    ) = NewTask(kind = kind, payload = "{}", availableAt = now, maxAttempts = 3, dedupKey = dedupKey)

    private fun claimFresh(kind: String = "test.kind"): ClaimedTask {
        queue.enqueue(newTask(kind = kind))
        return requireNotNull(queue.claimNext(now, Duration.ofMinutes(1)))
    }

    // --- enqueue / findById / countByState ---

    @Test
    fun `Given a new task, Then enqueue inserts it as PENDING`() {
        // When
        val task = queue.enqueue(newTask())
        // Then
        val stored = queue.findById(task.id)
        assertEquals(TaskState.PENDING, stored?.state)
        assertEquals("test.kind", stored?.kind)
        assertEquals(0, stored?.attempts)
    }

    @Test
    fun `Given a live task with a dedup key, Then re-enqueue coalesces to the existing task`() {
        // Given
        val first = queue.enqueue(newTask(dedupKey = "dk-1"))
        // When
        val second = queue.enqueue(newTask(dedupKey = "dk-1"))
        // Then
        assertEquals(first.id, second.id)
        assertEquals(1, queue.countByState(TaskState.PENDING))
    }

    @Test
    fun `Given a dedup key whose only task is terminal, Then enqueue creates a new task`() {
        // Given: ux_tasks_dedup covers PENDING and RUNNING only (`1.3.sql:27`), so a settled row is not a duplicate
        val dedupKey = createRandomString()
        val settled = queue.enqueue(newTask(dedupKey = dedupKey))
        val claimed = requireNotNull(queue.claimNext(now, Duration.ofMinutes(1)))
        queue.markSucceeded(claimed.id, claimed.leaseId, now)

        // When
        val enqueued = queue.enqueue(newTask(dedupKey = dedupKey))

        // Then: coalescing onto a finished task would drop the work the caller just asked for
        assertNotEquals(settled.id, enqueued.id)
        assertEquals(TaskState.SUCCEEDED, queue.findById(settled.id)?.state)
        assertEquals(TaskState.PENDING, queue.findById(enqueued.id)?.state)
    }

    @Test
    fun `Given no dedup key, Then two enqueues create two tasks`() {
        // Given / When
        queue.enqueue(newTask(dedupKey = null))
        queue.enqueue(newTask(dedupKey = null))
        // Then
        assertEquals(2, queue.countByState(TaskState.PENDING))
    }

    @Test
    fun `Given no task with the given id, Then findById returns null`() {
        // Given / When
        val stored = queue.findById(java.util.UUID.randomUUID())
        // Then
        assertNull(stored)
    }

    /**
     * Without one transaction around the dedup check and the insert, the pair races as two autocommit
     * statements (`agents/engineering.md`, "One connection"). No other test in this suite notices.
     */
    @Test
    fun `Given no ambient transaction, Then enqueue still inserts inside one`() {
        // Given
        val witness = TransactionWitnessPersistor(persistor, transactionControl)
        val witnessedQueue = EbeanTaskQueue(witness, transactionRunner, backoffPolicy)

        // When: nothing above opened a transaction
        witnessedQueue.enqueue(newTask())

        // Then
        assertEquals(true, witness.sawTransactionOnTaskInsert)
    }

    // --- enqueue: the dedup insert loses the race ---

    @Test
    fun `Given the dedup insert loses the race, Then enqueue returns the live task it collided with`() {
        // Given: a live task appears under the same dedup key between enqueue's check and its insert
        val dedupKey = createRandomString()
        val conflict = liveTaskModel(dedupKey)
        val racingQueue =
            EbeanTaskQueue(LosingDedupRacePersistor(persistor, conflict), transactionRunner, backoffPolicy)

        // When: no ambient transaction, so enqueue opens and commits its own
        val converged = racingQueue.enqueue(newTask(dedupKey = dedupKey))

        // Then
        assertEquals(conflict.id, converged.id)
        assertEquals(1, queue.countByState(TaskState.PENDING))
    }

    @Test
    fun `Given an ambient transaction, Then a lost dedup race still converges on the live task`() {
        // Given
        val dedupKey = createRandomString()
        val conflict = liveTaskModel(dedupKey)
        val racingQueue =
            EbeanTaskQueue(LosingDedupRacePersistor(persistor, conflict), transactionRunner, backoffPolicy)

        // When: the caller owns the transaction, so committing it shows the caught violation left it usable
        val converged =
            transactionControl.beginTransaction().use { transaction ->
                val task = racingQueue.enqueue(newTask(dedupKey = dedupKey))
                transaction.commit()
                task
            }

        // Then: the committed transaction holds the conflicting row and not the refused insert
        assertEquals(conflict.id, converged.id)
        assertEquals(1, queue.countByState(TaskState.PENDING))
    }

    @Test
    fun `Given a non-unique failure on the dedup insert, Then enqueue propagates it`() {
        // Given: a live task is there to converge on, so only the discriminator stops it being handed over
        val dedupKey = createRandomString()
        val conflict = liveTaskModel(dedupKey)
        val violation = notNullConstraintViolation()
        val persistorRaising = ForeignFailurePersistor(persistor, conflict, violation)
        val racingQueue = EbeanTaskQueue(persistorRaising, transactionRunner, backoffPolicy)

        // When, Then: a NOT NULL violation answered by convergence would hide a broken column
        val thrown =
            assertThrows(PersistenceException::class.java) {
                racingQueue.enqueue(newTask(dedupKey = dedupKey))
            }
        assertSame(violation, thrown)
    }

    @Test
    fun `Given a dedup violation with no live task behind it, Then enqueue propagates the violation`() {
        // Given: the violation carries no row to converge on, so propagating it is the honest answer
        val violation = uniqueConstraintViolation()
        val racingQueue =
            EbeanTaskQueue(NoConflictRowPersistor(persistor, violation), transactionRunner, backoffPolicy)

        // When, Then
        val thrown =
            assertThrows(PersistenceException::class.java) {
                racingQueue.enqueue(newTask(dedupKey = createRandomString()))
            }
        assertSame(violation, thrown)
    }

    /** A live row under [dedupKey]: what a lost race leaves for the losing insert to collide with. */
    private fun liveTaskModel(dedupKey: String) =
        TaskModel(
            id = UUID.randomUUID(),
            kind = "test.kind",
            payload = "{}",
            state = TaskState.PENDING.name,
            priority = 0,
            availableAt = now,
            attempts = 0,
            maxAttempts = 3,
            dedupKey = dedupKey,
        )

    /** The exception shape Ebean-on-SQLite raises for a unique-index violation. */
    private fun uniqueConstraintViolation() =
        PersistenceException(
            "[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed",
            SQLiteException(
                "[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed",
                SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE,
            ),
        )

    /** The shape of a failure that must never converge: same vendor errorCode 19, other resultCode. */
    private fun notNullConstraintViolation() =
        PersistenceException(
            "[SQLITE_CONSTRAINT_NOTNULL] A NOT NULL constraint failed",
            SQLiteException(
                "[SQLITE_CONSTRAINT_NOTNULL] A NOT NULL constraint failed",
                SQLiteErrorCode.SQLITE_CONSTRAINT_NOTNULL,
            ),
        )

    /** Records whether a transaction was open on the thread when the insert reached the store. */
    private class TransactionWitnessPersistor(
        private val delegate: Persistor,
        private val transactionControl: TransactionControl,
    ) : Persistor by delegate {
        var sawTransactionOnTaskInsert: Boolean? = null
            private set

        override fun save(bean: Any) {
            if (bean is TaskModel && sawTransactionOnTaskInsert == null) {
                sawTransactionOnTaskInsert = transactionControl.currentTransaction() != null
            }
            delegate.save(bean)
        }
    }

    /**
     * Stages a lost dedup race by writing [conflict] just before the insert: the real race does not reproduce
     * while `enqueue` holds its check and its insert in one transaction
     * (`docs/adr/0009-unique-index-named-outcomes.md`, findings).
     */
    private class LosingDedupRacePersistor(
        private val delegate: Persistor,
        private val conflict: TaskModel,
    ) : Persistor by delegate {
        private var raced = false

        override fun save(bean: Any) {
            if (bean is TaskModel && !raced) {
                raced = true
                delegate.save(conflict)
            }
            delegate.save(bean)
        }
    }

    /** Writes [conflict], then fails the insert with [violation]: only the discriminator stops convergence. */
    private class ForeignFailurePersistor(
        private val delegate: Persistor,
        private val conflict: TaskModel,
        private val violation: PersistenceException,
    ) : Persistor by delegate {
        override fun save(bean: Any) {
            if (bean is TaskModel && bean.dedupKey == conflict.dedupKey) {
                delegate.save(conflict)
                throw violation
            }
            delegate.save(bean)
        }
    }

    /** Raises [violation] on a task insert without writing anything, so the re-read finds nothing. */
    private class NoConflictRowPersistor(
        private val delegate: Persistor,
        private val violation: PersistenceException,
    ) : Persistor by delegate {
        override fun save(bean: Any) {
            if (bean is TaskModel) throw violation
            delegate.save(bean)
        }
    }

    // --- claimNext ---

    @Test
    fun `Given a runnable task, Then claimNext returns it as RUNNING with a lease and incremented attempts`() {
        // Given
        val enqueued = queue.enqueue(newTask())
        // When
        val claimed = queue.claimNext(now, Duration.ofMinutes(1))
        // Then
        assertEquals(enqueued.id, claimed?.id)
        assertEquals(1, claimed?.attempts)
        val stored = queue.findById(enqueued.id)
        assertEquals(TaskState.RUNNING, stored?.state)
        assertEquals(claimed?.leaseId, stored?.leaseId)
    }

    @Test
    fun `Given no runnable task, Then claimNext returns null`() {
        // When / Then
        assertNull(queue.claimNext(now, Duration.ofMinutes(1)))
    }

    @Test
    fun `Given a task available in the future, Then claimNext skips it`() {
        // Given
        queue.enqueue(newTask().copy(availableAt = now.plusSeconds(60)))
        // When / Then
        assertNull(queue.claimNext(now, Duration.ofMinutes(1)))
    }

    @Test
    fun `Given two runnable tasks with different priority, Then claimNext takes the higher priority`() {
        // Given
        queue.enqueue(newTask(kind = "low"))
        queue.enqueue(NewTask(kind = "high", payload = "{}", availableAt = now, priority = 10, maxAttempts = 3))
        // When
        val claimed = queue.claimNext(now, Duration.ofMinutes(1))
        // Then
        assertEquals("high", claimed?.kind)
    }

    // --- settle methods with fencing ---

    @Test
    fun `Given a claimed task, Then markSucceeded with the right lease succeeds`() {
        // Given
        val claimed = claimFresh()
        // When
        val ok = queue.markSucceeded(claimed.id, claimed.leaseId, now)
        // Then
        assertTrue(ok)
        assertEquals(TaskState.SUCCEEDED, queue.findById(claimed.id)?.state)
        assertEquals(now, queue.findById(claimed.id)?.terminalStateAt)
    }

    @Test
    fun `Given a wrong lease, Then markSucceeded is fenced and changes nothing`() {
        // Given
        val claimed = claimFresh()
        // When
        val ok = queue.markSucceeded(claimed.id, "wrong-lease", now)
        // Then
        assertFalse(ok)
        assertEquals(TaskState.RUNNING, queue.findById(claimed.id)?.state)
    }

    @Test
    fun `Given a claimed task, Then markPendingRetry reschedules it`() {
        // Given
        val claimed = claimFresh()
        val retryAt = now.plusSeconds(30)
        // When
        val ok = queue.markPendingRetry(claimed.id, claimed.leaseId, retryAt, now, "boom")
        // Then
        assertTrue(ok)
        val stored = queue.findById(claimed.id)
        assertEquals(TaskState.PENDING, stored?.state)
        assertEquals(retryAt, stored?.availableAt)
        assertEquals("boom", stored?.lastError)
        assertNull(stored?.leaseId)
        // A retry keeps the task live, so terminalStateAt stays unset.
        assertNull(stored?.terminalStateAt)
    }

    @Test
    fun `Given a wrong lease, Then markPendingRetry is fenced and changes nothing`() {
        // Given
        val claimed = claimFresh()
        val retryAt = now.plusSeconds(30)
        // When
        val ok = queue.markPendingRetry(claimed.id, "wrong-lease", retryAt, now, "boom")
        // Then
        assertFalse(ok)
        assertEquals(TaskState.RUNNING, queue.findById(claimed.id)?.state)
    }

    @Test
    fun `Given a claimed task, Then markDead settles it to DEAD`() {
        // Given
        val claimed = claimFresh()
        // When
        val ok = queue.markDead(claimed.id, claimed.leaseId, now, "fatal")
        // Then
        assertTrue(ok)
        assertEquals(TaskState.DEAD, queue.findById(claimed.id)?.state)
        assertEquals("fatal", queue.findById(claimed.id)?.lastError)
        assertEquals(now, queue.findById(claimed.id)?.terminalStateAt)
    }

    @Test
    fun `Given a wrong lease, Then markDead is fenced and changes nothing`() {
        // Given
        val claimed = claimFresh()
        // When
        val ok = queue.markDead(claimed.id, "wrong-lease", now, "fatal")
        // Then
        assertFalse(ok)
        assertEquals(TaskState.RUNNING, queue.findById(claimed.id)?.state)
    }

    @Test
    fun `Given no cancel request, Then markCancelledIfRequested returns false`() {
        // Given
        val claimed = claimFresh()
        // When
        val cancelled = queue.markCancelledIfRequested(claimed.id, claimed.leaseId, now)
        // Then
        assertFalse(cancelled)
        assertEquals(TaskState.RUNNING, queue.findById(claimed.id)?.state)
    }

    @Test
    fun `Given a cancel request on a running task, Then markCancelledIfRequested cancels it`() {
        // Given
        val claimed = claimFresh()
        queue.requestCancel(claimed.id)
        // When
        val cancelled = queue.markCancelledIfRequested(claimed.id, claimed.leaseId, now)
        // Then
        assertTrue(cancelled)
        assertEquals(TaskState.CANCELLED, queue.findById(claimed.id)?.state)
        assertEquals(now, queue.findById(claimed.id)?.terminalStateAt)
    }

    @Test
    fun `Given a cancel request but the wrong lease, Then markCancelledIfRequested is fenced and changes nothing`() {
        // Given
        val claimed = claimFresh()
        queue.requestCancel(claimed.id)
        // When
        val cancelled = queue.markCancelledIfRequested(claimed.id, "wrong-lease", now)
        // Then
        assertFalse(cancelled)
        assertEquals(TaskState.RUNNING, queue.findById(claimed.id)?.state)
    }

    // --- cancelPending / requestCancel ---

    @Test
    fun `Given a pending task, Then cancelPending cancels it`() {
        // Given
        val task = queue.enqueue(newTask())
        val cancelAt = now.plusSeconds(900)
        // When
        val ok = queue.cancelPending(task.id, cancelAt)
        // Then
        assertTrue(ok)
        assertEquals(TaskState.CANCELLED, queue.findById(task.id)?.state)
        assertEquals(cancelAt, queue.findById(task.id)?.terminalStateAt)
    }

    @Test
    fun `Given a running task, Then cancelPending does nothing`() {
        // Given
        val claimed = claimFresh()
        // When
        val ok = queue.cancelPending(claimed.id, now)
        // Then
        assertFalse(ok)
        assertEquals(TaskState.RUNNING, queue.findById(claimed.id)?.state)
        // A live task never enters a terminal state, so terminalStateAt stays unset.
        assertNull(queue.findById(claimed.id)?.terminalStateAt)
    }

    @Test
    fun `Given a running task, Then requestCancel sets the cancel flag`() {
        // Given
        val claimed = claimFresh()
        // When
        val ok = queue.requestCancel(claimed.id)
        // Then
        assertTrue(ok)
        assertTrue(queue.findById(claimed.id)?.cancelRequested ?: false)
    }

    @Test
    fun `Given a pending task, Then requestCancel does nothing`() {
        // Given
        val task = queue.enqueue(newTask())
        // When
        val ok = queue.requestCancel(task.id)
        // Then
        assertFalse(ok)
        assertFalse(queue.findById(task.id)?.cancelRequested ?: true)
    }

    // --- reapExpired ---

    @Test
    fun `Given a running task whose lease expired, Then reapExpired returns it to PENDING`() {
        // Given
        val claimed = claimFresh() // lease 1 minute from now
        val later = now.plusSeconds(120)
        // When
        val reaped = queue.reapExpired(later, NO_FLOORS)
        // Then
        assertEquals(1, reaped)
        val stored = queue.findById(claimed.id)
        assertEquals(TaskState.PENDING, stored?.state)
        assertNull(stored?.leaseId)
        // A reaped task is live again (PENDING), so terminalStateAt stays null (spec 4.1).
        assertNull(stored?.terminalStateAt)
    }

    @Test
    fun `Given a running task whose lease expired, Then reapExpired pushes its next attempt out`() {
        // Given
        val claimed = claimFresh() // lease 1 minute from now, first attempt
        val reapedAt = now.plusSeconds(120)

        // When
        val reaped = queue.reapExpired(reapedAt, NO_FLOORS)

        // Then: a reap spends an attempt, so it backs off like a returned failure. Left where the
        // claim put it, availableAt is in the past and the next poll re-claims within the second,
        // which spends the whole budget in seconds (spec 2026-08-14-user-data-import.md section 9).
        assertEquals(1, reaped)
        assertEquals(reapedAt.plus(BACKOFF_BASE), queue.findById(claimed.id)?.availableAt)
    }

    @Test
    fun `Given a running task with a live lease, Then reapExpired leaves it alone`() {
        // Given
        claimFresh()
        // When (before lease expiry)
        val reaped = queue.reapExpired(now.plusSeconds(1), NO_FLOORS)
        // Then
        assertEquals(0, reaped)
    }

    @Test
    fun `Given expired leases of two kinds, Then reapExpired floors the kind it was given a floor for`() {
        // Given: one kind whose floor the caller passes, one it does not name at all
        val floor = Duration.ofMinutes(10)
        val floored = claimFresh(kind = "floored.kind")
        val unfloored = claimFresh(kind = "unfloored.kind")
        val reapedAt = now.plusSeconds(120)

        // When
        val reaped = queue.reapExpired(reapedAt, mapOf("floored.kind" to floor))

        // Then: the jitter is full and the clock is a parameter, so both instants are exact rather
        // than bounds; at or above the floor would pass an adapter adding it to the window.
        assertEquals(2, reaped)
        assertEquals(
            reapedAt.plus(floor),
            queue.findById(floored.id)?.availableAt,
            "a reaped lease waits out its kind's floor, and no longer",
        )
        assertEquals(
            reapedAt.plus(BACKOFF_BASE),
            queue.findById(unfloored.id)?.availableAt,
            "a kind the caller names no floor for keeps the queue's own window",
        )
    }

    // --- attempts exhaustion ---

    @Test
    fun `Given a task that already used all its attempts, Then claimNext kills it instead of running it`() {
        // Given
        val enqueued = queue.enqueue(NewTask(kind = "test.kind", payload = "{}", availableAt = now, maxAttempts = 1))
        queue.claimNext(now, Duration.ofMinutes(1))
        queue.reapExpired(now.plusSeconds(120), NO_FLOORS)

        // When: past the backoff the reap wrote, since a task that is not yet available is not claimed
        // at all and would sit PENDING rather than being killed
        val killAt = now.plusSeconds(120).plus(BACKOFF_BASE)
        val reclaimed = queue.claimNext(killAt, Duration.ofMinutes(1))

        // Then
        assertNull(reclaimed)
        val stored = queue.findById(enqueued.id)
        assertEquals(TaskState.DEAD, stored?.state)
        assertEquals(1, stored?.attempts)
        assertNull(stored?.leaseId)
        assertEquals(killAt, stored?.terminalStateAt)
    }

    // --- renewLease ---

    @Test
    fun `Given a held lease, Then renewLease pushes the expiry back`() {
        // Given
        val claimed = claimFresh()
        val extendedUntil = now.plusSeconds(600)

        // When
        val renewed = queue.renewLease(claimed.id, claimed.leaseId, extendedUntil)

        // Then
        assertTrue(renewed)
        assertEquals(extendedUntil, queue.findById(claimed.id)?.leaseExpiresAt)
        assertEquals(0, queue.reapExpired(now.plusSeconds(120), NO_FLOORS))
    }

    @Test
    fun `Given a stale lease id, Then renewLease is fenced and changes nothing`() {
        // Given
        val claimed = claimFresh()

        // When
        val renewed = queue.renewLease(claimed.id, "wrong-lease", now.plusSeconds(600))

        // Then
        assertFalse(renewed)
        assertEquals(1, queue.reapExpired(now.plusSeconds(120), NO_FLOORS))
    }

    @Test
    fun `Given a settled task, Then renewLease refuses to revive its lease`() {
        // Given
        val claimed = claimFresh()
        queue.markSucceeded(claimed.id, claimed.leaseId, now)

        // When
        val renewed = queue.renewLease(claimed.id, claimed.leaseId, now.plusSeconds(600))

        // Then
        assertFalse(renewed)
        assertEquals(TaskState.SUCCEEDED, queue.findById(claimed.id)?.state)
    }

    // --- deleteTerminalBefore ---

    @Test
    fun `Given terminal and non-terminal tasks, Then deleteTerminalBefore deletes only stale terminals`() {
        // Given: for each terminal state (SUCCEEDED, DEAD, CANCELLED) one stale row back-dated before
        // the cutoff and one fresh row back-dated after it, plus a PENDING and a RUNNING task
        // back-dated before the cutoff. `terminal_state_at` is a plain column, but it is back-dated
        // via raw SQL rather than a re-save, to place the row on a specific side of the cutoff
        // independently of the `now` the transition itself stamped.
        val cutoff = storableNow()
        val beforeCutoff = cutoff.minus(2, ChronoUnit.HOURS)
        val afterCutoff = cutoff.plus(2, ChronoUnit.HOURS)

        val staleSucceeded = claimFresh().also { queue.markSucceeded(it.id, it.leaseId, now) }
        val freshSucceeded = claimFresh().also { queue.markSucceeded(it.id, it.leaseId, now) }
        val staleDead = claimFresh().also { queue.markDead(it.id, it.leaseId, now, "boom") }
        val freshDead = claimFresh().also { queue.markDead(it.id, it.leaseId, now, "boom") }
        val staleCancelled = queue.enqueue(newTask()).also { queue.cancelPending(it.id, now) }
        val freshCancelled = queue.enqueue(newTask()).also { queue.cancelPending(it.id, now) }
        val oldPending = queue.enqueue(newTask())
        val oldRunning = claimFresh()

        backDateTerminalStateAt(staleSucceeded.id, beforeCutoff)
        backDateTerminalStateAt(freshSucceeded.id, afterCutoff)
        backDateTerminalStateAt(staleDead.id, beforeCutoff)
        backDateTerminalStateAt(freshDead.id, afterCutoff)
        backDateTerminalStateAt(staleCancelled.id, beforeCutoff)
        backDateTerminalStateAt(freshCancelled.id, afterCutoff)
        backDateTerminalStateAt(oldPending.id, beforeCutoff)
        backDateTerminalStateAt(oldRunning.id, beforeCutoff)

        // When
        val deleted = queue.deleteTerminalBefore(cutoff)

        // Then: the three stale terminals are gone; fresh terminals and non-terminals are untouched
        // (the state filter excludes PENDING/RUNNING, the time filter excludes fresh terminals).
        assertEquals(3, deleted)
        assertNull(queue.findById(staleSucceeded.id))
        assertNull(queue.findById(staleDead.id))
        assertNull(queue.findById(staleCancelled.id))
        assertNotNull(queue.findById(freshSucceeded.id))
        assertNotNull(queue.findById(freshDead.id))
        assertNotNull(queue.findById(freshCancelled.id))
        assertNotNull(queue.findById(oldPending.id))
        assertNotNull(queue.findById(oldRunning.id))
    }

    @Test
    fun `Given no terminal task older than the cutoff, Then deleteTerminalBefore returns zero`() {
        // Given: every task is either non-terminal or a fresh terminal
        val cutoff = storableNow().minus(1, ChronoUnit.HOURS)
        val freshTerminal = claimFresh().also { queue.markSucceeded(it.id, it.leaseId, storableNow()) }
        val runningTask = claimFresh()

        // When
        val deleted = queue.deleteTerminalBefore(cutoff)

        // Then
        assertEquals(0, deleted)
        assertNotNull(queue.findById(freshTerminal.id))
        assertNotNull(queue.findById(runningTask.id))
    }

    private fun backDateTerminalStateAt(id: UUID, terminalStateAt: Instant) {
        database
            .sqlUpdate("UPDATE tasks SET terminal_state_at = ? WHERE id = ?")
            .setParameter(1, terminalStateAt)
            .setParameter(2, id)
            .execute()
    }

    private companion object {
        val NO_FLOORS: Map<String, Duration> = emptyMap()
        val BACKOFF_BASE: Duration = Duration.ofSeconds(30)
        val BACKOFF_CAP: Duration = Duration.ofMinutes(5)
    }
}
