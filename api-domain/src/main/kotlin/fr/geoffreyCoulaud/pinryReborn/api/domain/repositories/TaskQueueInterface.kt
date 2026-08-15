package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ClaimedTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.NewTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.Task
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Suppress("TooManyFunctions")
interface TaskQueueInterface {
    /**
     * Insert a PENDING task. If [NewTask.dedupKey] matches a live (PENDING/RUNNING) task,
     * returns that existing task without inserting.
     */
    fun enqueue(task: NewTask): Task

    /**
     * Atomically claim the highest-priority, earliest-available PENDING task whose
     * availableAt <= now, flipping it to RUNNING with a fresh lease. Returns null if none.
     */
    fun claimNext(now: Instant, leaseDuration: Duration): ClaimedTask?

    /**
     * Fenced lease extension: push the running task's lease expiry to [until] so a long handler is
     * not reclaimed from under itself. Returns false if the task is no longer running under
     * [leaseId], which tells the caller it has lost the task and must stop working on it.
     */
    fun renewLease(id: UUID, leaseId: String, until: Instant): Boolean

    /** Fenced settle to SUCCEEDED. Returns false if the lease no longer matches (fenced). */
    fun markSucceeded(id: UUID, leaseId: String, now: Instant): Boolean

    /** Fenced reschedule to PENDING at [availableAt] with an incremented-attempts row already claimed. */
    fun markPendingRetry(id: UUID, leaseId: String, availableAt: Instant, now: Instant, lastError: String?): Boolean

    /** Fenced settle to DEAD. */
    fun markDead(id: UUID, leaseId: String, now: Instant, lastError: String?): Boolean

    /**
     * Fenced: if cancelRequested is set on the leased row, settle to CANCELLED and return
     * true; otherwise return false.
     */
    fun markCancelledIfRequested(id: UUID, leaseId: String, now: Instant): Boolean

    /**
     * Cancel a PENDING task (guarded WHERE state=PENDING), stamping [now] as its terminal instant.
     * Returns true if it was cancelled.
     */
    fun cancelPending(id: UUID, now: Instant): Boolean

    /** Request cancellation of a RUNNING task (sets cancelRequested WHERE state=RUNNING). Returns true if set. */
    fun requestCancel(id: UUID): Boolean

    /**
     * Flip RUNNING rows whose lease expired back to PENDING, delayed by the queue's backoff floored
     * at [retryFloors] for the row's kind (a kind it does not name is unfloored). Returns the count.
     */
    fun reapExpired(now: Instant, retryFloors: Map<String, Duration>): Int

    /** Count tasks currently in [state]. For metrics/inspection. */
    fun countByState(state: TaskState): Int

    /** Read a task by id (tests/inspection). */
    fun findById(id: UUID): Task?

    /**
     * Delete tasks in a terminal state (`SUCCEEDED`, `DEAD`, `CANCELLED`) whose `terminalStateAt`
     * is before [cutoff]. Returns the count of rows deleted. Non-terminal tasks and fresh
     * terminals are untouched.
     */
    fun deleteTerminalBefore(cutoff: Instant): Int
}
