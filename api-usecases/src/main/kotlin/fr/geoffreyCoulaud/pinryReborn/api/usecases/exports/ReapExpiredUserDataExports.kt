package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.deleteQuietly
import java.time.Duration

/**
 * Purges expired `READY` exports (deletes their archive bytes, then moves the row to `EXPIRED`)
 * and sweeps orphaned staged files left behind by builds that died mid-write.
 *
 * Deliberately not `@ApplicationScoped` yet: `stagedFileMaxAge` (a raw `Duration`) and
 * [ExportArchiveStore] have no CDI producer until the wiring task (`ExportProducers`, Task 10), so
 * annotating this bean now would fail Quarkus's build-time bean validation in `api-application`.
 * Same precedent as `UserDataExportRequester`.
 */
class ReapExpiredUserDataExports(
    private val repository: UserDataExportRepositoryInterface,
    private val archiveStore: ExportArchiveStore,
    private val clock: Clock,
    private val stagedFileMaxAge: Duration,
) {
    fun reap(): Int {
        val now = clock.now()
        val expired = repository.findExpiredReadyExports(now)
        for (export in expired) {
            export.storageKey?.let { archiveStore.deleteQuietly(it) }
            repository.save(export.copy(state = UserDataExportState.EXPIRED))
        }
        archiveStore.discardOrphanedStagedFiles(now.minus(stagedFileMaxAge))
        return expired.size
    }
}
