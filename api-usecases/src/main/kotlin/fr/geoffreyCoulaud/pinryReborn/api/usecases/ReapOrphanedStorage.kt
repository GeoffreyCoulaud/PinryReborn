package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StorageLayout
import java.util.UUID

/**
 * Reclaims orphaned rendition subtrees, export archives and import archives on disk: residue no
 * row-based sweep can see (a committed account delete that left bytes behind, a crash mid-write,
 * residue from before this change). Disk drives the iteration and each batch is checked against the
 * DB, so memory is bounded by [batchSize] regardless of how many files or rows an active instance
 * accumulates. The garbage collection bounds residue, not live data (spec
 * docs/specs/2026-07-27-periodic-gc.md, decision D5).
 *
 * Not `@ApplicationScoped`: [batchSize] is a primitive ARC cannot resolve, so the bean is produced
 * in wiring (`GarbageCollectionProducers`), mirroring `ExportProducers` for `ReapUserDataExports`.
 *
 * Logger-free: the `*Quietly` extensions log per-item failures, and the lifecycle `safeAll` logs a
 * sweep-level throw. Add no logger here.
 */
@Suppress("LongParameterList") // One port and one repository per dataset, plus the batch bound.
class ReapOrphanedStorage(
    private val renditionCache: RenditionCache,
    private val exportArchiveStore: ExportArchiveStore,
    private val importArchiveStore: ImportArchiveStore,
    private val imageRepository: ImageRepositoryInterface,
    private val userDataExportRepository: UserDataExportRepositoryInterface,
    private val userDataImportRepository: UserDataImportRepositoryInterface,
    private val batchSize: Int,
) {
    /**
     * Reclaim every orphaned rendition subtree and archive on disk, batched by [batchSize]. Returns the
     * total count of orphans identified for reclamation across the three halves: per-item
     * eviction/deletion is best-effort via the `*Quietly` extensions, so a failed delete is logged at
     * WARN and retried on the next sweep, not counted here as a success.
     */
    fun reap(): Int {
        var reclaimed = 0
        renditionCache.forEachImageIdOnDisk { ids ->
            ids.chunked(batchSize).forEach { chunk ->
                val missing = imageRepository.findMissingImageIds(chunk)
                missing.forEach { id -> renditionCache.evictImageQuietly(id) }
                reclaimed += missing.size
            }
        }
        exportArchiveStore.forEachStorageKeyOnDisk { keys ->
            reclaimed += reclaimOrphans(keys, EXPORTS_PREFIX, userDataExportRepository::findMissingExportIds) {
                exportArchiveStore.deleteQuietly(it)
            }
        }
        importArchiveStore.forEachStorageKeyOnDisk { keys ->
            reclaimed += reclaimOrphans(keys, IMPORTS_PREFIX, userDataImportRepository::findMissingImportIds) {
                importArchiveStore.deleteQuietly(it)
            }
        }
        return reclaimed
    }

    /** The same walk over both archive datasets: parse, batch, ask the database, delete what it disowns. */
    private fun reclaimOrphans(
        keys: Sequence<String>,
        prefix: String,
        missingIds: (Collection<UUID>) -> Set<UUID>,
        delete: (String) -> Unit,
    ): Int {
        val parsed = keys.mapNotNull { key -> parseId(key, prefix)?.let { id -> id to key } }
        var reclaimed = 0
        parsed.chunked(batchSize).forEach { chunk ->
            val missing = missingIds(chunk.map { it.first })
            val toDelete = chunk.filter { (id, _) -> id in missing }
            toDelete.forEach { (_, key) -> delete(key) }
            reclaimed += toDelete.size
        }
        return reclaimed
    }

    /**
     * The id in a `<prefix><uuid>.<ext>` key. Null on any failure, and a key that does not parse is
     * skipped, never deleted, and never passed to the repository.
     */
    private fun parseId(storageKey: String, prefix: String): UUID? {
        if (!storageKey.startsWith(prefix)) return null
        val fileName = storageKey.removePrefix(prefix)
        val idText = fileName.substringBeforeLast('.', "")
        val extension = fileName.substringAfterLast('.', "")
        return if (idText.isEmpty() || extension.isEmpty()) null
        else runCatching { UUID.fromString(idText) }.getOrNull()
    }

    private companion object {
        const val EXPORTS_PREFIX = "${StorageLayout.EXPORTS_DIRECTORY}/"
        const val IMPORTS_PREFIX = "${StorageLayout.IMPORTS_DIRECTORY}/"
    }
}
