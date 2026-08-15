package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.CancelTask
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Deletes a user data export on request (spec `docs/specs/2026-07-22-user-data-export.md` §6):
 * owner-checked through [UserDataExportGetter], then one fenced write of `DELETED` over any state
 * that is not already `isGone`, the release taken from the state that write replaced. A row already
 * gone is left alone, there being nothing left to release.
 *
 * [CancelTask.cancel]'s `Boolean` result is deliberately ignored: whether the task was still
 * cancellable or had already settled, the export is being deleted either way, so branching on it
 * would create a side with no observable difference and no way to test it.
 */
@ApplicationScoped
@Suppress("UnsafeCallOnNullableType")
class UserDataExportDeleter(
    private val getter: UserDataExportGetter,
    private val repository: UserDataExportRepositoryInterface,
    private val archiveStore: ExportArchiveStore,
    private val cancelTask: CancelTask,
    private val transactionRunner: TransactionRunner,
) {
    fun delete(user: User, exportId: UUID) {
        getter.get(user, exportId)
        // The write first, and the release from the state it replaced: the state read before the fence
        // may be one state old, and each arm releases what only that state's owner is not holding.
        val deleted = markDeleted(exportId) ?: return
        when (deleted.state) {
            // The Boolean is dropped deliberately (class KDoc). No archive exists in this state: the
            // build stamps its key before it promotes, so the bytes it names may not be there yet.
            UserDataExportState.PENDING -> deleted.taskId?.let { cancelTask.cancel(it) }
            // Deliberately propagating, NOT deleteQuietly: this delete IS the user's primary
            // DELETE /me/exports/{id} operation, not a side-effect cleanup, so D1 (every storage
            // cleanup is best-effort) does not apply. The row moved first, so a disk failure leaves a
            // row promising less than it holds rather than more (`docs/adr/0016`, decision 4).
            UserDataExportState.READY -> archiveStore.delete(deleted.storageKey!!)
            // FAILED, the only other state the fence lets through: no bytes, and a settled attempt.
            else -> Unit
        }
    }

    /**
     * The state alone, on the row as it is now, answering the row it wrote over: a build advances this
     * row while the request runs, and one already gone keeps the state that says why.
     */
    private fun markDeleted(exportId: UUID): UserDataExport? =
        repository.saveFencedOver(transactionRunner, exportId, { !it.state.isGone }) {
            it.copy(state = UserDataExportState.DELETED)
        }
}
