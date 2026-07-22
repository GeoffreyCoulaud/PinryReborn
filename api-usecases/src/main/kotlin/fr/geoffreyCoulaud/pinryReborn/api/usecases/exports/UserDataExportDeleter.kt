package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.CancelTask
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Deletes a user data export on request (spec `docs/specs/2026-07-22-user-data-export.md` §6):
 * owner-checked through [UserDataExportGetter], then per state -- `PENDING` cancels its task (if
 * any) and moves to `DELETED`; `READY` deletes its bytes and moves to `DELETED`; any other state
 * (already `isGone`, or `FAILED`) is a no-op, since there is nothing left to release.
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
) {
    fun delete(user: User, exportId: UUID) {
        val export = getter.get(user, exportId)
        when (export.state) {
            UserDataExportState.PENDING -> {
                export.taskId?.let { cancelTask.cancel(it) }
                repository.save(export.copy(state = UserDataExportState.DELETED))
            }
            UserDataExportState.READY -> {
                archiveStore.delete(export.storageKey!!)
                repository.save(export.copy(state = UserDataExportState.DELETED))
            }
            else -> Unit
        }
    }
}
