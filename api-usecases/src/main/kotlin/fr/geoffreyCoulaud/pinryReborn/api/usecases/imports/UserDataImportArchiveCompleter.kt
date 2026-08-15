package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.Task
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.deleteQuietly
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportArchiveEmptyError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportNotAwaitingArchiveError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.EnqueueTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.UserDataImportTask
import jakarta.enterprise.context.ApplicationScoped
import java.nio.file.NoSuchFileException
import java.util.UUID

/** Closes an upload and hands the archive to the worker (spec §6). */
@ApplicationScoped
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
        val staged = onTheUpload { archiveStore.finishUpload(importId) }
        val storageKey = ImportArchiveKey.forImport(importId)
        // Named before the bytes are there, as the export builder does: a completer that dies right
        // after the promote still leaves an archive some row points at. Fenced ahead of the promote, so
        // a cancellation caught here refuses before an upload the canceller unlinked is moved.
        repository.saveWhileAwaitingArchive(transactionRunner, importId) {
            it.copy(storageKey = storageKey, byteSize = staged.byteSize)
        }
        onTheUpload { archiveStore.promote(staged, storageKey) }
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
     * A cancellation writes `CANCELLED` and then unlinks the upload, and nothing re-reads the row
     * between the fence and the store call: the caller is told now what its next `GET` would say.
     */
    private fun <T> onTheUpload(touch: () -> T): T =
        try {
            touch()
        } catch (error: NoSuchFileException) {
            throw ImportNotAwaitingArchiveError(error)
        }

    /**
     * The transition, the task and the row naming it in one transaction: a failure anywhere leaves an
     * `AWAITING_ARCHIVE` row the sweep covers, not a `PENDING` one no sweep selects and no task serves.
     */
    private fun handOver(importId: UUID): UserDataImport =
        // Opened here rather than borrowed from a helper: the fence is lexical, and so is the rule that
        // holds it, so the read and both writes stay inside the block a reader can see.
        transactionRunner.inTransaction {
            val current =
                repository.findById(importId)?.takeIf { it.awaitsItsArchive() }
                    ?: throw ImportNotAwaitingArchiveError()
            // The transition before the enqueue: a worker claiming a row still awaiting its archive
            // would return without running it and leave the import to be swept instead.
            val pending =
                repository.save(
                    current.copy(state = UserDataImportState.PENDING, archiveCompletedAt = clock.now()),
                )
            repository.save(pending.copy(taskId = enqueued(importId).id))
        }

    private fun enqueued(importId: UUID): Task =
        enqueueTask.enqueue(
            kind = UserDataImportTask.KIND,
            payload = importId.toString(),
            maxAttempts = UserDataImportTask.MAX_ATTEMPTS,
            priority = UserDataImportTask.PRIORITY,
        )
}
