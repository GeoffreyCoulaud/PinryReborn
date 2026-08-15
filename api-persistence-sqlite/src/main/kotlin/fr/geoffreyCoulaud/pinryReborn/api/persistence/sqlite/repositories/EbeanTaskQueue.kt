package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.BackoffPolicy
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ClaimedTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.NewTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.Task
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.Persistor
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.TaskModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.TaskModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QTaskModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.PersistenceException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID

/**
 * Ebean-backed implementation of [TaskQueueInterface].
 *
 * Settle/cancel operations are fenced bulk updates guarded by `id` + `leaseId` (or `state`),
 * using the row-count returned by [io.ebean.UpdateQuery.update] as the success signal instead
 * of loading, mutating and saving the entity. This avoids racing with a concurrent claim/settle
 * on the same row.
 *
 * [enqueue]'s dedup check-then-insert, [claimNext]'s select-then-update and [reapExpired]'s
 * select-then-save are each wrapped in one [TransactionRunner.inTransaction] block, so the pair
 * serializes instead of racing across two auto-commit statements. A caller's transaction is joined,
 * which is Ebean's REQUIRED semantics.
 */
@ApplicationScoped
// TaskQueueInterface itself has exactly 11 methods (the port's minimal surface); a full,
// non-partial adapter necessarily implements all of them as members, tripping detekt's default
// per-class threshold. Suppressed rather than split, since splitting would fragment one cohesive
// adapter across artificial classes for no readability gain.
@Suppress("TooManyFunctions")
class EbeanTaskQueue(
    private val persistor: Persistor,
    private val transactionRunner: TransactionRunner,
    private val backoffPolicy: BackoffPolicy,
) : TaskQueueInterface {
    override fun enqueue(task: NewTask): Task = transactionRunner.inTransaction { enqueueWithin(task) }

    private fun enqueueWithin(task: NewTask): Task {
        val dedupKey = task.dedupKey
        return if (dedupKey == null) insert(task) else enqueueDeduplicated(task, dedupKey)
    }

    /** Converges on the live task [dedupKey] already names, whether the read finds it or the insert collides. */
    private fun enqueueDeduplicated(task: NewTask, dedupKey: String): Task {
        val existing = findLiveTaskWithDedupKey(dedupKey)
        if (existing != null) return existing.toDomain()
        return try {
            insert(task)
        } catch (error: PersistenceException) {
            SqliteConstraintViolations.onUniqueConstraint(error) {
                val live = findLiveTaskWithDedupKey(dedupKey) ?: throw error
                live.toDomain()
            }
        }
    }

    private fun findLiveTaskWithDedupKey(dedupKey: String): TaskModel? =
        QTaskModel()
            .dedupKey.equalTo(dedupKey)
            .state.isIn(PartialUniqueIndexStates.liveTaskStates)
            .findOne()

    private fun insert(task: NewTask): Task {
        val model =
            TaskModel(
                id = randomUUID(),
                kind = task.kind,
                payload = task.payload,
                state = TaskState.PENDING.name,
                priority = task.priority,
                availableAt = task.availableAt,
                attempts = 0,
                maxAttempts = task.maxAttempts,
                dedupKey = task.dedupKey,
            )
        persistor.save(model)
        return model.toDomain()
    }

    override fun findById(id: UUID): Task? = QTaskModel().id.equalTo(id).findOne()?.toDomain()

    override fun countByState(state: TaskState): Int = QTaskModel().state.equalTo(state.name).findCount()

    override fun claimNext(
        now: Instant,
        leaseDuration: Duration,
    ): ClaimedTask? =
        transactionRunner.inTransaction {
            val model =
                QTaskModel()
                    .state.equalTo(TaskState.PENDING.name)
                    .availableAt.le(now)
                    .orderBy("priority desc, availableAt asc, id asc")
                    .setMaxRows(1)
                    .findOne()
            if (model == null) {
                return@inTransaction null
            }
            // A task whose handler never returns is never settled, so its attempts are only ever
            // spent by the reaper putting it back to PENDING. Without this guard such a task is
            // claimed again forever. Killing it here (rather than skipping to the next candidate)
            // keeps the claim a single-row operation: the next poll picks up whatever follows.
            if (model.attempts >= model.maxAttempts) {
                model.state = TaskState.DEAD.name
                model.lastError = "attempts exhausted"
                model.leaseId = null
                model.leaseExpiresAt = null
                model.terminalStateAt = now
                persistor.save(model)
                return@inTransaction null
            }
            val leaseId = randomUUID().toString()
            model.state = TaskState.RUNNING.name
            model.leaseId = leaseId
            model.leaseExpiresAt = now.plus(leaseDuration)
            model.attempts += 1
            persistor.save(model)
            ClaimedTask(
                id = model.id,
                kind = model.kind,
                payload = model.payload,
                attempts = model.attempts,
                maxAttempts = model.maxAttempts,
                leaseId = leaseId,
                cancelRequested = model.cancelRequested,
            )
        }

    override fun renewLease(
        id: UUID,
        leaseId: String,
        until: Instant,
    ): Boolean =
        leaseGuard(id, leaseId)
            .state.equalTo(TaskState.RUNNING.name)
            .asUpdate()
            .set("leaseExpiresAt", until)
            .setRaw("version = version + 1")
            .update() > 0

    override fun markSucceeded(
        id: UUID,
        leaseId: String,
        now: Instant,
    ): Boolean =
        leaseGuard(id, leaseId)
            .asUpdate()
            .set("state", TaskState.SUCCEEDED.name)
            .set("terminalStateAt", now)
            .setRaw("version = version + 1")
            .update() > 0

    override fun markPendingRetry(
        id: UUID,
        leaseId: String,
        availableAt: Instant,
        now: Instant,
        lastError: String?,
    ): Boolean =
        leaseGuard(id, leaseId)
            .asUpdate()
            .set("state", TaskState.PENDING.name)
            .set("availableAt", availableAt)
            .set("lastError", lastError)
            .setNull("leaseId")
            .setNull("leaseExpiresAt")
            .setRaw("version = version + 1")
            .update() > 0

    override fun markDead(
        id: UUID,
        leaseId: String,
        now: Instant,
        lastError: String?,
    ): Boolean =
        leaseGuard(id, leaseId)
            .asUpdate()
            .set("state", TaskState.DEAD.name)
            .set("lastError", lastError)
            .set("terminalStateAt", now)
            .setRaw("version = version + 1")
            .update() > 0

    override fun markCancelledIfRequested(
        id: UUID,
        leaseId: String,
        now: Instant,
    ): Boolean =
        leaseGuard(id, leaseId)
            .cancelRequested.equalTo(true)
            .asUpdate()
            .set("state", TaskState.CANCELLED.name)
            .set("terminalStateAt", now)
            .setRaw("version = version + 1")
            .update() > 0

    override fun cancelPending(
        id: UUID,
        now: Instant,
    ): Boolean =
        QTaskModel()
            .id.equalTo(id)
            .state.equalTo(TaskState.PENDING.name)
            .asUpdate()
            .set("state", TaskState.CANCELLED.name)
            .set("terminalStateAt", now)
            .setRaw("version = version + 1")
            .update() > 0

    override fun requestCancel(id: UUID): Boolean =
        QTaskModel()
            .id.equalTo(id)
            .state.equalTo(TaskState.RUNNING.name)
            .asUpdate()
            .set("cancelRequested", true)
            .setRaw("version = version + 1")
            .update() > 0

    /**
     * Row by row rather than in one bulk update: the delay is read off the attempts that row spent.
     * The pair is a transaction for the reason [claimNext]'s is, a settle landing between the two.
     */
    override fun reapExpired(now: Instant): Int =
        transactionRunner.inTransaction {
            val expired =
                QTaskModel()
                    .state.equalTo(TaskState.RUNNING.name)
                    .leaseExpiresAt.le(now)
                    .findList()
            expired.forEach { model ->
                model.state = TaskState.PENDING.name
                model.lastError = "reclaimed after lease expiry"
                model.leaseId = null
                model.leaseExpiresAt = null
                // A reap spends an attempt, so it delays the next one as a returned failure does. The
                // handler's own floor is not applied here: the queue cannot see it (spec section 9).
                model.availableAt = backoffPolicy.nextAttemptAt(model.attempts, now, Duration.ZERO)
                persistor.save(model)
            }
            expired.size
        }

    override fun deleteTerminalBefore(cutoff: Instant): Int =
        QTaskModel()
            .state.isIn(TaskState.SUCCEEDED.name, TaskState.DEAD.name, TaskState.CANCELLED.name)
            .terminalStateAt.lessThan(cutoff)
            .delete()

    /** Query for the task row identified by [id], guarded by its current [leaseId] (fencing). */
    private fun leaseGuard(
        id: UUID,
        leaseId: String,
    ) = QTaskModel().id.equalTo(id).leaseId.equalTo(leaseId)
}
