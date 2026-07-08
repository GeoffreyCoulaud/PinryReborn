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

    /** Cancel a PENDING task (guarded WHERE state=PENDING). Returns true if it was cancelled. */
    fun cancelPending(id: UUID): Boolean

    /** Request cancellation of a RUNNING task (sets cancelRequested WHERE state=RUNNING). Returns true if set. */
    fun requestCancel(id: UUID): Boolean

    /** Flip RUNNING rows whose lease expired (leaseExpiresAt <= now) back to PENDING. Returns the count reclaimed. */
    fun reapExpired(now: Instant): Int

    /** Count tasks currently in [state]. For metrics/inspection. */
    fun countByState(state: TaskState): Int

    /** Read a task by id (tests/inspection). */
    fun findById(id: UUID): Task?
}
