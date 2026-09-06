package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What one sweep moved, per pass: a row met by two passes is counted in both, and each answers alone. */
data class ExportSweepCounts(val failed: Int, val expired: Int, val reclaimed: Int)

/**
 * The export lifecycle sweep (spec `docs/specs/2026-08-27-export-build-completion.md` section 4.3):
 * fails a build nothing is driving any more, expires a `READY` archive past its retention, reclaims
 * the bytes of terminal rows, and drops staged files past their age.
 *
 * One rule holds the three together: a state transition writes the state and nothing else, and
 * reclaiming bytes is pass 3's job for every terminal state. Passes 2 and 3 would otherwise take the
 * same row in one run, count it twice and issue the same delete twice.
 *
 * Deliberately not `@ApplicationScoped`: the two `Duration`s and the batch size are plain values ARC
 * cannot resolve, so annotating this bean would fail Quarkus's build-time bean validation in
 * `api-application`. `ExportProducers` is the single place that constructs it.
 *
 * Each row is isolated in its own try/catch and a failure is logged at WARN rather than aborting the
 * batch: one bad row must not leave the rest unswept that run. Same shape as `ReapTombstonedAccounts`.
 */
@Suppress("LongParameterList") // Four ports, the clock, the two Durations and the bound ARC cannot resolve.
class ReapUserDataExports(
    private val repository: UserDataExportRepositoryInterface,
    private val archiveStore: ExportArchiveStore,
    private val taskQueue: TaskQueueInterface,
    private val clock: Clock,
    private val transactionRunner: TransactionRunner,
    private val interruptedGrace: Duration,
    private val stagedFileMaxAge: Duration,
    private val sweepBatchSize: Int,
) {
    /** In this order: passes 1 and 2 both make a row terminal, and so reclaimable in the same run. */
    fun reap(): ExportSweepCounts {
        val now = clock.now()
        val failed = failInterruptedBuilds(now)
        val expired = expireReadyExports(now)
        val reclaimed = reclaimTerminalArchives()
        discardStagedFiles(now)
        return ExportSweepCounts(failed, expired, reclaimed)
    }

    /** Its own net, as a row has: a refused walk of the staging directory must not cost the three counts. */
    @Suppress("TooGenericExceptionCaught")
    private fun discardStagedFiles(now: Instant) {
        try {
            archiveStore.discardOrphanedStagedFiles(now.minus(stagedFileMaxAge))
        } catch (e: Exception) {
            logger.warn(e) { "export staging sweep failed" }
        }
    }

    /**
     * The grace dominates the longest plausible staging, since `claimNext` kills a task without regard
     * for a handler still running: condemning early writes FAILED under a builder holding an archive.
     */
    private fun failInterruptedBuilds(now: Instant): Int {
        val condemnedBefore = now.minus(interruptedGrace)
        return repository.findPending(sweepBatchSize)
            .filter { it.requestedAt.isBefore(condemnedBefore) && it.lostItsTask() }
            .count { swept(it.id) { failInterrupted(it.id) } }
    }

    /**
     * Live is [TaskState.isLiveAttempt], shared with the import sweep so the set has one source.
     * Absent means the terminal task sweep deleted it, never "not enqueued yet".
     */
    private fun UserDataExport.lostItsTask(): Boolean {
        val task = taskId?.let { taskQueue.findById(it) } ?: return true
        return !task.state.isLiveAttempt
    }

    private fun failInterrupted(exportId: UUID): Boolean =
        repository.saveFenced(transactionRunner, exportId, { it.state == UserDataExportState.PENDING }) {
            it.copy(state = UserDataExportState.FAILED, failureCode = EXPORT_INTERRUPTED)
        } != null

    private fun expireReadyExports(now: Instant): Int =
        repository.findExpiredReadyExports(now).count { swept(it.id) { expire(it.id) } }

    /** The state and nothing else: the bytes of every terminal row are pass 3's, this row's included. */
    private fun expire(exportId: UUID): Boolean =
        repository.saveFenced(transactionRunner, exportId, ::stillReady) {
            it.copy(state = UserDataExportState.EXPIRED)
        } != null

    /** Refused when the row moved on, and the one place that can name the state that took the window. */
    private fun stillReady(export: UserDataExport): Boolean {
        if (export.state == UserDataExportState.READY) return true
        logger.info { "export ${export.id} is ${export.state}, expected READY: this sweep writes nothing" }
        return false
    }

    private fun reclaimTerminalArchives(): Int =
        repository.findReclaimableTerminal(sweepBatchSize).count { export -> swept(export.id) { reclaim(export) } }

    /**
     * The bytes before the column (`docs/adr/0017`, decision 3): the column is this sweep's only index
     * into the residue, so stamping over a failed delete hides it from the one pass that can name it.
     */
    private fun reclaim(export: UserDataExport): Boolean {
        val derived = ExportArchiveKey.forExport(export.id, archiveStore.format.fileExtension)
        // Both keys when they differ: the derived one names a dead builder's archive, the column's what
        // this row claims, and deleting the derived one alone succeeds vacuously over the other.
        setOfNotNull(derived, export.storageKey).forEach { archiveStore.delete(it) }
        return repository.saveFenced(transactionRunner, export.id, { it.state.isTerminal }) {
            it.copy(storageKey = null)
        } != null
    }

    /**
     * Item-level isolation, as `ReapUserDataImports` has: one row the store or the database
     * refuses must not leave the rest of the sweep undone, and either can throw anything.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun swept(exportId: UUID, sweep: () -> Boolean): Boolean =
        try {
            sweep()
        } catch (e: Exception) {
            logger.warn(e) { "export sweep failed for export $exportId" }
            false
        }

    private companion object {
        private val logger = KotlinLogging.logger {}

        /** The failure code for a build no attempt is coming back to, which the user reads on the row. */
        const val EXPORT_INTERRUPTED = "EXPORT_INTERRUPTED"
    }
}
