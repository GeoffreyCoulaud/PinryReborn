package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.deleteQuietly
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.CancelTask
import java.util.UUID

/**
 * Cancels an import (spec §6). Rows already written stay: an import is not a transaction, and what
 * it created belongs to the account. Not `@ApplicationScoped`: [ImportArchiveStore] has no producer yet.
 */
class UserDataImportCanceller(
    private val getter: UserDataImportGetter,
    private val repository: UserDataImportRepositoryInterface,
    private val archiveStore: ImportArchiveStore,
    private val cancelTask: CancelTask,
) {
    fun cancel(user: User, importId: UUID) {
        val userDataImport = getter.get(user, importId)
        when (userDataImport.state) {
            UserDataImportState.AWAITING_ARCHIVE -> {
                archiveStore.discardPartialUpload(importId)
                markCancelled(userDataImport)
            }
            // The Boolean is dropped deliberately: cancel() answers true both for a task cancelled
            // before it ran and for one already RUNNING, so it cannot say whether a runner holds these
            // bytes. Accepted race: a worker that claimed the task while the row still read PENDING
            // loses its archive and lands on FAILED rather than CANCELLED, costing no data and no bytes.
            UserDataImportState.PENDING -> {
                userDataImport.taskId?.let { cancelTask.cancel(it) }
                archiveStore.deleteQuietly(ImportArchiveKey.forImport(importId))
                markCancelled(userDataImport)
            }
            // The archive is left alone: the fence stops the walk at the next pin and the runner
            // deletes it as it returns, so deleting here would pull the file out from under a live read.
            UserDataImportState.RUNNING -> markCancelled(userDataImport)
            else -> Unit
        }
    }

    private fun markCancelled(userDataImport: UserDataImport) {
        repository.save(userDataImport.copy(state = UserDataImportState.CANCELLED))
    }
}
