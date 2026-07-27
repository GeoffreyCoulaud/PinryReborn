package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportAlreadyInProgressException
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.Reauthenticator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.deleteQuietly
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ExportAlreadyInProgressError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ExportTooSoonError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.EnqueueTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.UserDataExportTask
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Requests a user data export archive, behind step-up re-authentication.
 *
 * At most one live archive per user: a `PENDING` request is refused with
 * [ExportAlreadyInProgressError], and requests within [minimumInterval] of the last one (any state)
 * are refused with [ExportTooSoonError]. Any `READY` export is superseded (its bytes deleted only
 * after the transaction commits, never inside it, so a rollback never leaves a `READY` row pointing
 * at bytes that no longer exist).
 *
 * Deliberately not `@ApplicationScoped` yet: `minimumInterval` (a raw `Duration`) and
 * `ExportArchiveStore` have no CDI producer until the wiring task (`ExportProducers`), so annotating
 * this bean now would fail Quarkus's build-time bean validation in `api-application`. Same precedent
 * as `FilesystemZipExportArchiveStore` (Task 5).
 */
@Suppress("LongParameterList")
class UserDataExportRequester(
    private val repository: UserDataExportRepositoryInterface,
    private val archiveStore: ExportArchiveStore,
    private val enqueueTask: EnqueueTask,
    private val reauthenticator: Reauthenticator,
    private val clock: Clock,
    private val transactionRunner: TransactionRunner,
    private val minimumInterval: Duration,
) {
    fun request(user: User, factor: String): UserDataExport {
        reauthenticator.reauthenticate(user, factor)
        val (export, supersededKey) = transactionRunner.inTransaction { createPending(user) }
        // Outside the transaction on purpose: deleting inside means a later rollback leaves a READY
        // row pointing at bytes that no longer exist, which serves a 500 instead of a clean error.
        // Best-effort: the transaction has committed, so a disk failure here must not 500 a request
        // that already succeeded. The orphan archive is reclaimed by the periodic garbage collection.
        supersededKey?.let { archiveStore.deleteQuietly(it) }
        return export
    }

    private fun createPending(user: User): Pair<UserDataExport, String?> {
        val now = clock.now()
        if (repository.findPendingForUser(user.id) != null) throw ExportAlreadyInProgressError()
        val last = repository.findLastRequestedAtForUser(user.id)
        val earliest = now.minus(minimumInterval)
        if (last != null && last.isAfter(earliest)) {
            throw ExportTooSoonError(Duration.between(earliest, last).seconds.coerceAtLeast(1))
        }
        val ready = repository.findReadyForUser(user.id)
        ready?.let { repository.save(it.copy(state = UserDataExportState.SUPERSEDED, storageKey = null)) }
        val export = savePending(user, now)
        val task =
            enqueueTask.enqueue(
                kind = UserDataExportTask.KIND,
                payload = export.id.toString(),
                maxAttempts = UserDataExportTask.MAX_ATTEMPTS,
            )
        return repository.save(export.copy(taskId = task.id)) to ready?.storageKey
    }

    /**
     * The `findPendingForUser` check above handles the ordinary case, but it loses the race between
     * two concurrent requests: the partial unique index is what actually catches the second `PENDING`
     * row in that case, and the persistence adapter surfaces it as the domain
     * [ExportAlreadyInProgressException]. Translate it into the use-case error here.
     */
    private fun savePending(user: User, now: Instant): UserDataExport =
        try {
            repository.save(
                UserDataExport(
                    id = UUID.randomUUID(), userId = user.id, state = UserDataExportState.PENDING,
                    formatVersion = EXPORT_FORMAT_VERSION, requestedAt = now,
                ),
            )
        } catch (error: ExportAlreadyInProgressException) {
            throw ExportAlreadyInProgressError(error)
        }

    private companion object {
        const val EXPORT_FORMAT_VERSION = 1
    }
}
