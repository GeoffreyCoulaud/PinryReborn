package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveTooLargeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportChunkOffsetMismatchException
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportArchiveTooLargeError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportChunkOffsetMismatchError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportInsufficientStorageError
import java.io.InputStream
import java.util.UUID

/**
 * Appends one chunk of an import archive (spec §6). Deliberately not `@ApplicationScoped`: its two
 * bounds are plain scalars with no producer until the wiring task, as the export's requester was.
 */
class UserDataImportChunkReceiver(
    private val repository: UserDataImportRepositoryInterface,
    private val archiveStore: ImportArchiveStore,
    private val clock: Clock,
    private val transactionRunner: TransactionRunner,
    private val maxArchiveBytes: Long,
    private val minimumFreeBytes: Long,
) {
    /**
     * The state is read twice and the second read decides: the chunk streams to disk in between, and a
     * save of the copy read first would restore `AWAITING_ARCHIVE` over a cancellation, and over no bytes.
     */
    fun receive(
        user: User,
        importId: UUID,
        offset: Long,
        bytes: InputStream,
    ): UserDataImport {
        repository.findAwaitingArchive(user, importId)
        // Checked before every chunk: the default deployment points every data directory at the volume
        // that also holds the database, so an unbounded upload takes the instance down, not the import.
        if (!archiveStore.hasFreeSpace(minimumFreeBytes)) throw ImportInsufficientStorageError()
        val uploadedBytes = append(importId, offset, bytes)
        return repository.saveWhileAwaitingArchive(transactionRunner, importId) {
            it.copy(uploadedBytes = uploadedBytes, lastUploadActivityAt = clock.now())
        }
    }

    /** Both refusals leave the length as it was, so the row is not stamped and the client resumes. */
    private fun append(
        importId: UUID,
        offset: Long,
        bytes: InputStream,
    ): Long =
        try {
            archiveStore.appendChunk(importId, offset, bytes, maxArchiveBytes)
        } catch (error: ImportChunkOffsetMismatchException) {
            throw ImportChunkOffsetMismatchError(currentLength = error.currentLength, cause = error)
        } catch (error: ImportArchiveTooLargeException) {
            throw ImportArchiveTooLargeError(error)
        }
}
