package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The import lifecycle sweep (spec §6): abandons uploads nobody fed, fails walks whose task is gone,
 * reclaims the bytes of terminal rows, and drops staged files past their age. Built by `ImportProducers`.
 */
@Suppress("LongParameterList") // Four ports, the clock, and the two Durations ARC cannot resolve.
class ReapAbandonedUserDataImports(
    private val repository: UserDataImportRepositoryInterface,
    private val archiveStore: ImportArchiveStore,
    private val taskQueue: TaskQueueInterface,
    private val clock: Clock,
    private val transactionRunner: TransactionRunner,
    private val uploadGrace: Duration,
    private val stagedFileMaxAge: Duration,
) {
    /**
     * The rows acted on, counting an abandonment, a failure and a reclamation alike. Abandonment runs
     * first: it is what makes a row holding a promoted archive terminal, and reclaimable in this run.
     */
    fun reap(): Int {
        val now = clock.now()
        val reaped = abandonStaleUploads(now) + failInterruptedRuns() + reclaimTerminalArchives()
        archiveStore.discardOrphanedStagedFiles(now.minus(stagedFileMaxAge))
        return reaped
    }

    private fun abandonStaleUploads(now: Instant): Int =
        repository.findAbandonableBefore(now.minus(uploadGrace)).count { swept(it.id) { abandon(it.id) } }

    /**
     * The state first, the file after: a fence refused here means the upload was completed while this
     * run read it, and unlinking then would take the source of an archive being promoted.
     */
    private fun abandon(importId: UUID): Boolean {
        repository.saveFenced(transactionRunner, importId, { it.awaitsItsArchive() }) {
            it.copy(state = UserDataImportState.ABANDONED)
        } ?: return false
        archiveStore.discardPartialUpload(importId)
        return true
    }

    private fun failInterruptedRuns(): Int =
        repository.findRunning().filter { it.lostItsTask() }.count { swept(it.id) { failInterrupted(it.id) } }

    /**
     * A live attempt is a task `PENDING` or `RUNNING`, lease expiry included; every other state has
     * settled. Absent means the terminal task sweep deleted it, never "not enqueued yet".
     */
    private fun UserDataImport.lostItsTask(): Boolean {
        val task = taskId?.let { taskQueue.findById(it) } ?: return true
        return task.state !in LIVE_ATTEMPT_STATES
    }

    private fun failInterrupted(importId: UUID): Boolean =
        repository.saveFenced(transactionRunner, importId, { it.state == UserDataImportState.RUNNING }) {
            it.copy(state = UserDataImportState.FAILED, failureCode = IMPORT_INTERRUPTED)
        } != null

    private fun reclaimTerminalArchives(): Int =
        repository.findReclaimableTerminal().count { swept(it.id) { reclaim(it.id) } }

    /**
     * The bytes go before the row stops naming them: stamping over a failed delete would hide residue
     * from the only sweep that can still name it. Derived key, so a dead completer's archive is named.
     */
    private fun reclaim(importId: UUID): Boolean {
        archiveStore.delete(ImportArchiveKey.forImport(importId))
        return repository.saveFenced(transactionRunner, importId, { it.state.isTerminal }) {
            it.copy(storageKey = null)
        } != null
    }

    /**
     * Item-level isolation, as `ReapExpiredUserDataExports` has: one row the store or the database
     * refuses must not leave the rest of the hour's sweep undone, and either can throw anything.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun swept(importId: UUID, sweep: () -> Boolean): Boolean =
        try {
            sweep()
        } catch (e: Exception) {
            logger.warn(e) { "import sweep failed for import $importId" }
            false
        }

    private companion object {
        private val logger = KotlinLogging.logger {}

        /** The two states a task the queue still owes this row can be in (spec section 6). */
        private val LIVE_ATTEMPT_STATES = setOf(TaskState.PENDING, TaskState.RUNNING)

        /** Spec section 10's failure code for a walk whose attempt is not coming back. */
        const val IMPORT_INTERRUPTED = "IMPORT_INTERRUPTED"
    }
}
