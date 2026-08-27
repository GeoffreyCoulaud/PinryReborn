package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.deleteQuietly
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.CancelTask
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Deletes a user data export on request (spec `docs/specs/2026-07-22-user-data-export.md` §6):
 * owner-checked through [UserDataExportGetter], then one fenced write of `DELETED` over any state
 * that is not already `isGone`, the release taken from the state that write replaced. A row already
 * gone keeps the state that says why, and the bytes it still names are released again, that replay
 * being the fast repair for a first attempt whose release failed after its write landed; the export
 * sweep's third pass is the guaranteed one.
 *
 * Only the `READY` arm propagates a storage failure, because that delete is the user's own
 * operation. The other two are residue cleanup and best-effort (`docs/adr/0003`, decision 1): the
 * `PENDING` arm releases the key a build derives, which is where a promote whose transaction rolled
 * back left its bytes (`docs/adr/0017`, decision 2).
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
            // The Boolean is dropped deliberately (class KDoc). The key is derived rather than read:
            // the row names nothing, and a promote whose transaction rolled back is unreachable to
            // every fence. No window against a live attempt, since the two transactions serialise.
            UserDataExportState.PENDING -> {
                deleted.taskId?.let { cancelTask.cancel(it) }
                archiveStore.deleteQuietly(derivedKey(deleted.id))
            }
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
     * Decided on the copy read before the fence, whose key no path here clears: a gone row still names
     * the bytes a first release lost. Best-effort, the sweep's third pass being the guaranteed repair.
     */
    private fun releaseStranded(found: UserDataExport) {
        if (!found.state.isGone) return
        found.storageKey?.let { archiveStore.deleteQuietly(it) }
    }

    /** Derived rather than read: the residue this releases is bytes no row ever recorded a key for. */
    private fun derivedKey(exportId: UUID): String =
        ExportArchiveKey.forExport(exportId, archiveStore.format.fileExtension)

    /**
     * The state alone, on the row as it is now, answering the row it wrote over: a build advances this
     * row while the request runs, and one already gone keeps the state that says why.
     */
    private fun markDeleted(exportId: UUID): UserDataExport? =
        repository.saveFencedOver(transactionRunner, exportId, { !it.state.isGone }) {
            it.copy(state = UserDataExportState.DELETED)
        }
}
