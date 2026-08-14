package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.deleteQuietly
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportArchiveEmptyError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportNotAwaitingArchiveError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.EnqueueTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.UserDataImportTask
import java.util.UUID

/**
 * Closes an upload and hands the archive to the worker (spec §6). Deliberately not
 * `@ApplicationScoped`: [ImportArchiveStore] has no producer until the wiring task.
 */
class UserDataImportArchiveCompleter(
    private val repository: UserDataImportRepositoryInterface,
    private val archiveStore: ImportArchiveStore,
    private val enqueueTask: EnqueueTask,
    private val clock: Clock,
    private val transactionRunner: TransactionRunner,
) {
    /**
     * Every write here is fenced: the window before the first is the feature's widest, an fsync and a
     * digest of up to twenty gigabytes, and a `DELETE` landing in it takes the upload with it.
     */
    fun complete(user: User, importId: UUID): UserDataImport {
        val userDataImport = repository.findAwaitingArchive(user, importId)
        // The row is the authority on what the upload received; with nothing behind it, the store would
        // open an upload file no chunk ever created and raise an untyped IOException.
        if (userDataImport.uploadedBytes == 0L) throw ImportArchiveEmptyError()
        val staged = archiveStore.finishUpload(importId)
        val storageKey = ImportArchiveKey.forImport(importId)
        // Named before the bytes are there, as the export builder does: a completer that dies right
        // after the promote still leaves an archive some row points at. Fenced ahead of the promote, so
        // a cancellation caught here refuses before an upload the canceller unlinked is moved.
        repository.saveWhileAwaitingArchive(transactionRunner, importId) {
            it.copy(storageKey = storageKey, byteSize = staged.byteSize)
        }
        archiveStore.promote(staged, storageKey)
        return try {
            handOver(importId)
        } catch (error: ImportNotAwaitingArchiveError) {
            // Promoted bytes no row will ever name again. Best effort, as everywhere: the request is
            // already refused, and ADR 0003's sweep is the guarantor if this delete fails.
            archiveStore.deleteQuietly(storageKey)
            throw error
        }
    }

    /**
     * The transition first, the task after: a worker claiming a row still awaiting its archive would
     * return without running it and leave the import to be swept instead.
     */
    private fun handOver(importId: UUID): UserDataImport {
        repository.saveWhileAwaitingArchive(transactionRunner, importId) {
            it.copy(state = UserDataImportState.PENDING, archiveCompletedAt = clock.now())
        }
        return transactionRunner.inTransaction { enqueued(importId) }
    }

    /**
     * ADR 0009's pair, in one transaction: the task and the only row that names it commit together, so a
     * lost fence takes the task with it rather than leaving one enqueued against a cancelled import.
     */
    private fun enqueued(importId: UUID): UserDataImport {
        val task =
            enqueueTask.enqueue(
                kind = UserDataImportTask.KIND,
                payload = importId.toString(),
                maxAttempts = UserDataImportTask.MAX_ATTEMPTS,
                priority = UserDataImportTask.PRIORITY,
            )
        // Not fenced on PENDING: a worker can claim the row before this write lands, and a RUNNING row
        // still has to keep the task id the sweep reads to tell a dead attempt from a live one.
        return repository.saveFenced(transactionRunner, importId, { !it.state.isTerminal }) {
            it.copy(taskId = task.id)
        } ?: throw ImportNotAwaitingArchiveError()
    }
}
