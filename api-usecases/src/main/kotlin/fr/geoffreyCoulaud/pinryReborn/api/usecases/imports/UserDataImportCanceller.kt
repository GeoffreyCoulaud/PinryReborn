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
        getter.get(user, importId)
        // The write first, and the release from the phase it replaced: the phase read before the fence
        // may be one phase old, and each arm releases what only that phase's owner is not holding.
        val cancelled = markCancelled(importId) ?: return
        when (cancelled.state) {
            // The state before the file, as the sweep does it: a fence lost to a completion means this
            // upload is a promoted archive's source. No task exists in this phase, so none is cancelled.
            UserDataImportState.AWAITING_ARCHIVE -> archiveStore.discardPartialUpload(importId)
            // The Boolean is dropped deliberately: cancel() answers true both for a task cancelled before
            // it ran and for one already RUNNING, so it cannot say whether a runner holds these bytes.
            UserDataImportState.PENDING -> {
                cancelled.taskId?.let { cancelTask.cancel(it) }
                archiveStore.deleteQuietly(ImportArchiveKey.forImport(importId))
            }
            // RUNNING, the only other state the fence lets through: the fence stops the walk at the next
            // pin and the runner deletes the archive as it returns, so deleting here would pull the file
            // out from under a live read.
            else -> Unit
        }
    }

    /**
     * The state alone, on the row as it is now, answering the row it wrote over: the runner advances
     * this row's counters while the request runs, and one that went terminal keeps its outcome.
     */
    private fun markCancelled(importId: UUID): UserDataImport? =
        repository.saveFencedOver(transactionRunner, importId, { !it.state.isTerminal }) {
            it.copy(state = UserDataImportState.CANCELLED)
        }
}
