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
 * gone keeps the state that says why, and the bytes it still names are released again, that replay
 * being the only repair for a first attempt whose release failed after its write landed.
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
        val found = getter.get(user, exportId)
        when (val deleted = markDeleted(exportId)) {
            null -> releaseStranded(found)
            else -> release(deleted)
        }
    }

    /**
     * The release taken from the state the write replaced: the state read before the fence may be one
     * state old, and each arm releases what only that state's owner is not holding.
     */
    private fun release(deleted: UserDataExport) {
        when (deleted.state) {
            // The Boolean is dropped deliberately (class KDoc). Nothing is released here even though
            // a build between its promote and its publish holds bytes already: that build's publish
            // meets this DELETED row at its own fence and deletes them itself.
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
     * The bytes a release that failed after its write left behind. Decided on the copy read before the
     * fence, unlike an accepted write's arm: a gone state is terminal and never clears its key.
     */
    private fun releaseStranded(found: UserDataExport) {
        if (!found.state.isGone) return
        found.storageKey?.let { archiveStore.delete(it) }
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
