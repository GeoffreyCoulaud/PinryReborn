package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SweepPages
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The import lifecycle sweep (spec §6): abandons uploads nobody fed, fails walks whose task is gone,
 * reclaims the bytes of terminal rows, and drops staged files past their age. Built by `ImportProducers`.
 */
@Suppress("LongParameterList") // Four ports, the clock, the two Durations and the bound ARC cannot resolve.
class ReapUserDataImports(
    private val repository: UserDataImportRepositoryInterface,
    private val archiveStore: ImportArchiveStore,
    private val taskQueue: TaskQueueInterface,
    private val clock: Clock,
    private val transactionRunner: TransactionRunner,
    private val uploadGrace: Duration,
    private val stagedFileMaxAge: Duration,
    private val sweepBatchSize: Int,
) {
    /**
     * The rows acted on, counting an abandonment, a failure and a reclamation alike. The transitions run
     * first: `failInterruptedRuns` is what makes a key-holding row terminal, so reclaimable in this run.
     */
    fun reap(): Int {
        val now = clock.now()
        val reaped = abandonStaleUploads(now) + failInterruptedRuns() + reclaimTerminalArchives()
        archiveStore.discardOrphanedStagedFiles(now.minus(stagedFileMaxAge))
        return reaped
    }

    private fun abandonStaleUploads(now: Instant): Int {
        val idleSince = now.minus(uploadGrace)
        return SweepPages
            .of(UserDataImport::id) { afterId -> repository.findAbandonableBefore(idleSince, afterId, sweepBatchSize) }
            .count { swept(it.id) { abandon(it.id) } }
    }

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
        SweepPages.of(UserDataImport::id) { afterId -> repository.findRunning(afterId, sweepBatchSize) }
            .filter { it.lostItsTask() }
            .count { swept(it.id) { failInterrupted(it.id) } }

    /**
     * Live is [TaskState.isLiveAttempt], shared with the export sweep so the set has one source.
     * Absent means the terminal task sweep deleted it, never "not enqueued yet".
     */
    private fun UserDataImport.lostItsTask(): Boolean {
        val task = taskId?.let { taskQueue.findById(it) } ?: return true
        return !task.state.isLiveAttempt
    }

    private fun failInterrupted(importId: UUID): Boolean =
        repository.saveFenced(transactionRunner, importId, { it.state == UserDataImportState.RUNNING }) {
            it.copy(state = UserDataImportState.FAILED, failureCode = IMPORT_INTERRUPTED)
        } != null

    private fun reclaimTerminalArchives(): Int =
        SweepPages.of(UserDataImport::id) { afterId -> repository.findReclaimableTerminal(afterId, sweepBatchSize) }
            .count { swept(it.id) { reclaim(it.id) } }

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
     * Item-level isolation, as `ReapUserDataExports` has: one row the store or the database
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

        /** Spec section 10's failure code for a walk whose attempt is not coming back. */
        const val IMPORT_INTERRUPTED = "IMPORT_INTERRUPTED"
    }
}
