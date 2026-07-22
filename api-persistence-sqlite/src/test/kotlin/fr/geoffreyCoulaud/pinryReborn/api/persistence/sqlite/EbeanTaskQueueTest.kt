package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ClaimedTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.NewTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.EbeanTaskQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class EbeanTaskQueueTest : RepositoryTest() {
    private val queue = EbeanTaskQueue(database)
    private val now = Instant.parse("2026-07-08T00:00:00Z")

    private fun newTask(
        kind: String = "test.kind",
        dedupKey: String? = null,
    ) = NewTask(kind = kind, payload = "{}", availableAt = now, maxAttempts = 3, dedupKey = dedupKey)

    private fun claimFresh(): ClaimedTask {
        queue.enqueue(newTask())
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
        // When
        val ok = queue.cancelPending(task.id)
        // Then
        assertTrue(ok)
        assertEquals(TaskState.CANCELLED, queue.findById(task.id)?.state)
    }

    @Test
    fun `Given a running task, Then cancelPending does nothing`() {
        // Given
        val claimed = claimFresh()
        // When
        val ok = queue.cancelPending(claimed.id)
        // Then
        assertFalse(ok)
        assertEquals(TaskState.RUNNING, queue.findById(claimed.id)?.state)
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
        val reaped = queue.reapExpired(later)
        // Then
        assertEquals(1, reaped)
        val stored = queue.findById(claimed.id)
        assertEquals(TaskState.PENDING, stored?.state)
        assertNull(stored?.leaseId)
    }

    @Test
    fun `Given a running task with a live lease, Then reapExpired leaves it alone`() {
        // Given
        claimFresh()
        // When (before lease expiry)
        val reaped = queue.reapExpired(now.plusSeconds(1))
        // Then
        assertEquals(0, reaped)
    }

    // --- attempts exhaustion ---

    @Test
    fun `Given a task that already used all its attempts, Then claimNext kills it instead of running it`() {
        // Given
        val enqueued = queue.enqueue(NewTask(kind = "test.kind", payload = "{}", availableAt = now, maxAttempts = 1))
        queue.claimNext(now, Duration.ofMinutes(1))
        queue.reapExpired(now.plusSeconds(120))

        // When
        val reclaimed = queue.claimNext(now.plusSeconds(121), Duration.ofMinutes(1))

        // Then
        assertNull(reclaimed)
        val stored = queue.findById(enqueued.id)
        assertEquals(TaskState.DEAD, stored?.state)
        assertEquals(1, stored?.attempts)
        assertNull(stored?.leaseId)
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
        assertEquals(0, queue.reapExpired(now.plusSeconds(120)))
    }

    @Test
    fun `Given a stale lease id, Then renewLease is fenced and changes nothing`() {
        // Given
        val claimed = claimFresh()

        // When
        val renewed = queue.renewLease(claimed.id, "wrong-lease", now.plusSeconds(600))

        // Then
        assertFalse(renewed)
        assertEquals(1, queue.reapExpired(now.plusSeconds(120)))
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
}
