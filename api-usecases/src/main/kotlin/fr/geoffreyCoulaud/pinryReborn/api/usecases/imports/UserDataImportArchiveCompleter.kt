package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
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
) {
    fun complete(user: User, importId: UUID): UserDataImport {
        val userDataImport = repository.findAwaitingArchive(user, importId)
        val staged = archiveStore.finishUpload(importId)
        val storageKey = ImportArchiveKey.forImport(importId)
        // Named before the bytes are there, as the export builder does: a completer that dies right
        // after the promote still leaves an archive some row points at.
        val keyed = repository.save(userDataImport.copy(storageKey = storageKey, byteSize = staged.byteSize))
        archiveStore.promote(staged, storageKey)
        val pending =
            repository.save(keyed.copy(state = UserDataImportState.PENDING, archiveCompletedAt = clock.now()))
        // Enqueued after that write, never before: a worker claiming the task while the row still said
        // AWAITING_ARCHIVE would return without running it, and the import would be swept instead.
        val task =
            enqueueTask.enqueue(
                kind = UserDataImportTask.KIND,
                payload = importId.toString(),
                maxAttempts = UserDataImportTask.MAX_ATTEMPTS,
                priority = UserDataImportTask.PRIORITY,
            )
        return repository.save(pending.copy(taskId = task.id))
    }
}
