package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.deleteQuietly
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.CancelTask
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Cancels an import (spec §6). Rows already written stay: an import is not a transaction, and what
 * it created belongs to the account.
 */
@ApplicationScoped
class UserDataImportCanceller(
    private val getter: UserDataImportGetter,
    private val repository: UserDataImportRepositoryInterface,
    private val archiveStore: ImportArchiveStore,
    private val cancelTask: CancelTask,
    private val transactionRunner: TransactionRunner,
) {
    fun cancel(user: User, importId: UUID) {
        val userDataImport = getter.get(user, importId)
        when (userDataImport.state) {
            UserDataImportState.AWAITING_ARCHIVE -> {
                archiveStore.discardPartialUpload(importId)
                markCancelled(importId)
            }
            // The state first, and everything else from the row that write read: a request whose fence
            // was refused releases nothing. The Boolean is dropped deliberately: cancel() answers true
            // both for a task cancelled before it ran and for one already RUNNING, so it cannot say
            // whether a runner holds these bytes.
            UserDataImportState.PENDING ->
                markCancelled(importId)?.let { cancelled ->
                    cancelled.taskId?.let { cancelTask.cancel(it) }
                    archiveStore.deleteQuietly(ImportArchiveKey.forImport(importId))
                }
            // The archive is left alone: the fence stops the walk at the next pin and the runner
            // deletes it as it returns, so deleting here would pull the file out from under a live read.
            UserDataImportState.RUNNING -> markCancelled(importId)
            else -> Unit
        }
    }

    /**
     * The state alone, on the row as it is now, answering it back: the runner advances this row's
     * counters while the request runs, and one that went terminal in that window keeps its outcome.
     */
    private fun markCancelled(importId: UUID): UserDataImport? =
        repository.saveFenced(transactionRunner, importId, { !it.state.isTerminal }) {
            it.copy(state = UserDataImportState.CANCELLED)
        }
}
