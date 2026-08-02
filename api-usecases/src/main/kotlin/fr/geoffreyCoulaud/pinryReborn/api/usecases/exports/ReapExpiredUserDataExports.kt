package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.deleteQuietly
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration

/**
 * Purges expired `READY` exports (deletes their archive bytes, then moves the row to `EXPIRED`)
 * and sweeps orphaned staged files left behind by builds that died mid-write.
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
    private val stagedFileMaxAge: Duration,
) {
    /**
     * Returns the number of expired exports identified, the same accounting the other GC reapers
     * use: a per-item re-save is best-effort, so a throw is logged and the next export is still
     * processed.
     */
    fun reap(): Int {
        val now = clock.now()
        val expired = repository.findExpiredReadyExports(now)
        expired.forEach(::reapOne)
        archiveStore.discardOrphanedStagedFiles(now.minus(stagedFileMaxAge))
        return expired.size
    }

    // The save can throw anything; item-level isolation is the point (class KDoc).
    @Suppress("TooGenericExceptionCaught")
    private fun reapOne(export: UserDataExport) {
        try {
            export.storageKey?.let { archiveStore.deleteQuietly(it) }
            repository.save(export.copy(state = UserDataExportState.EXPIRED))
        } catch (e: Exception) {
            logger.warn(e) { "export reap failed for export ${export.id}" }
        }
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}
