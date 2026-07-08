package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ClaimedTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.NewTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.Task
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.TaskModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.TaskModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QTaskModel
import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped
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
 * [enqueue]'s dedup check-then-insert and [claimNext]'s select-then-update are each wrapped in a
 * single explicit [io.ebean.Transaction] (`database.beginTransaction()`), so on the single-connection
 * SQLite datasource the whole read+write pair serializes atomically instead of racing across two
 * separate auto-commit statements.
 */
@ApplicationScoped
// TaskQueueInterface itself has exactly 11 methods (the port's minimal surface); a full,
// non-partial adapter necessarily implements all of them as members, tripping detekt's default
// per-class threshold. Suppressed rather than split, since splitting would fragment one cohesive
// adapter across artificial classes for no readability gain.
@Suppress("TooManyFunctions")
class EbeanTaskQueue(
    private val database: Database,
) : TaskQueueInterface {
    override fun enqueue(task: NewTask): Task =
        database.beginTransaction().use { transaction ->
            if (task.dedupKey != null) {
                val existing =
                    QTaskModel(database)
                        .dedupKey.equalTo(task.dedupKey)
                        .state.isIn(TaskState.PENDING.name, TaskState.RUNNING.name)
                        .findOne()
                if (existing != null) {
                    transaction.commit()
                    return@use existing.toDomain()
                }
            }
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
            database.save(model)
            transaction.commit()
            model.toDomain()
        }

    override fun findById(id: UUID): Task? = QTaskModel(database).id.equalTo(id).findOne()?.toDomain()

    override fun countByState(state: TaskState): Int = QTaskModel(database).state.equalTo(state.name).findCount()

    override fun claimNext(
        now: Instant,
        leaseDuration: Duration,
    ): ClaimedTask? =
        database.beginTransaction().use { transaction ->
            val model =
                QTaskModel(database)
                    .state.equalTo(TaskState.PENDING.name)
                    .availableAt.le(now)
                    .orderBy("priority desc, availableAt asc, id asc")
                    .setMaxRows(1)
                    .findOne()
            if (model == null) {
                transaction.commit()
                return@use null
            }
            val leaseId = randomUUID().toString()
            model.state = TaskState.RUNNING.name
            model.leaseId = leaseId
            model.leaseExpiresAt = now.plus(leaseDuration)
            model.attempts += 1
            database.save(model)
            transaction.commit()
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

    override fun markSucceeded(
        id: UUID,
        leaseId: String,
        now: Instant,
    ): Boolean =
        leaseGuard(id, leaseId)
            .asUpdate()
            .set("state", TaskState.SUCCEEDED.name)
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
            .setRaw("version = version + 1")
            .update() > 0

    override fun cancelPending(id: UUID): Boolean =
        QTaskModel(database)
            .id.equalTo(id)
            .state.equalTo(TaskState.PENDING.name)
            .asUpdate()
            .set("state", TaskState.CANCELLED.name)
            .setRaw("version = version + 1")
            .update() > 0

    override fun requestCancel(id: UUID): Boolean =
        QTaskModel(database)
            .id.equalTo(id)
            .state.equalTo(TaskState.RUNNING.name)
            .asUpdate()
            .set("cancelRequested", true)
            .setRaw("version = version + 1")
            .update() > 0

    override fun reapExpired(now: Instant): Int =
        QTaskModel(database)
            .state.equalTo(TaskState.RUNNING.name)
            .leaseExpiresAt.le(now)
            .asUpdate()
            .set("state", TaskState.PENDING.name)
            .set("lastError", "reclaimed after lease expiry")
            .setNull("leaseId")
            .setNull("leaseExpiresAt")
            .setRaw("version = version + 1")
            .update()

    /** Query for the task row identified by [id], guarded by its current [leaseId] (fencing). */
    private fun leaseGuard(
        id: UUID,
        leaseId: String,
    ) = QTaskModel(database).id.equalTo(id).leaseId.equalTo(leaseId)
}
