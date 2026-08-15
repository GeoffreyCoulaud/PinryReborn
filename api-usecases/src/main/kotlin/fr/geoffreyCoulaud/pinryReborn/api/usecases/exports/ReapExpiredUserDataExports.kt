package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.deleteQuietly
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.util.UUID

/**
 * Purges expired `READY` exports (moves the row to `EXPIRED`, then deletes its archive bytes) and
 * sweeps orphaned staged files left behind by builds that died mid-write.
 *
 * Deliberately not `@ApplicationScoped` yet: `stagedFileMaxAge` (a raw `Duration`) and
 * [ExportArchiveStore] have no CDI producer until the wiring task (`ExportProducers`, Task 10), so
 * annotating this bean now would fail Quarkus's build-time bean validation in `api-application`.
 * Same precedent as `UserDataExportRequester`.
 *
 * Each export is isolated in its own try/catch and a failure (the repository save, in practice) is
 * logged at WARN rather than aborting the batch: one bad export must not leave the rest unswept
 * that run. Same shape as `ReapTombstonedAccounts`.
 */
class ReapExpiredUserDataExports(
    private val repository: UserDataExportRepositoryInterface,
    private val archiveStore: ExportArchiveStore,
    private val clock: Clock,
    private val transactionRunner: TransactionRunner,
    private val stagedFileMaxAge: Duration,
) {
    /**
     * The rows moved, not the rows selected: the batch is read once and written one by one, so a row
     * an owner deleted in between is refused, and a refused sweep must not read as a successful one.
     */
    fun reap(): Int {
        val now = clock.now()
        val reaped = repository.findExpiredReadyExports(now).count { expired(it.id) }
        archiveStore.discardOrphanedStagedFiles(now.minus(stagedFileMaxAge))
        return reaped
    }

    // The write can throw anything; item-level isolation is the point (class KDoc).
    @Suppress("TooGenericExceptionCaught")
    private fun expired(exportId: UUID): Boolean =
        try {
            expire(exportId)
        } catch (e: Exception) {
            logger.warn(e) { "export reap failed for export $exportId" }
            false
        }

    /**
     * The row before the bytes (`docs/adr/0016`, decision 4): the reverse order leaves a `READY` row
     * naming bytes that are gone whenever the write fails, and a download answering 500 instead of 410.
     */
    private fun expire(exportId: UUID): Boolean {
        val expired =
            repository.saveFenced(transactionRunner, exportId, ::stillReady) {
                it.copy(state = UserDataExportState.EXPIRED)
            } ?: return false
        expired.storageKey?.let { archiveStore.deleteQuietly(it) }
        return true
    }

    /** Refused when the row moved on, and the one place that can name the state that took the window. */
    private fun stillReady(export: UserDataExport): Boolean {
        if (export.state == UserDataExportState.READY) return true
        logger.info { "export ${export.id} is ${export.state}, expected READY: this sweep writes nothing" }
        return false
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}
