package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import java.util.UUID

/**
 * Reclaims orphaned rendition subtrees and export archives on disk: residue no row-based sweep can
 * see (a committed account delete that left bytes behind, a crash mid-write, residue from before
 * this change). Disk drives the iteration and each batch is checked against the DB, so memory is
 * bounded by [batchSize] regardless of how many files or rows an active instance accumulates. The
 * GC bounds residue, not live data (spec docs/specs/2026-07-27-periodic-gc.md, decision D5).
 *
 * Not `@ApplicationScoped`: [batchSize] is a primitive ARC cannot resolve, so the bean is produced
 * in wiring (T10's `GcProducers`), mirroring `ExportProducers` for `ReapExpiredUserDataExports`.
 *
 * Logger-free: the `*Quietly` extensions log per-item failures, and the lifecycle `safeAll` logs a
 * sweep-level throw. Add no logger here.
 */
class ReapOrphanedStorage(
    private val renditionCache: RenditionCache,
    private val exportArchiveStore: ExportArchiveStore,
    private val imageRepository: ImageRepositoryInterface,
    private val userDataExportRepository: UserDataExportRepositoryInterface,
    private val batchSize: Int,
) {
    /**
     * Reclaim every orphaned rendition subtree and export archive on disk, batched by [batchSize].
     * Returns the total count reclaimed across both halves.
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
            val parsed = keys.mapNotNull { key -> parseExportId(key)?.let { id -> id to key } }
            parsed.chunked(batchSize).forEach { chunk ->
                val missingIds = userDataExportRepository.findMissingExportIds(chunk.map { it.first })
                val toDelete = chunk.filter { (id, _) -> id in missingIds }
                toDelete.forEach { (_, key) -> exportArchiveStore.deleteQuietly(key) }
                reclaimed += toDelete.size
            }
        }
        return reclaimed
    }

    /**
     * Parse the export id from a storage key of the form `exports/<uuid>.<ext>`: strip the
     * `exports/` prefix and the extension, then parse the remainder as a [UUID]. Returns null on
     * any failure (wrong prefix, missing or empty extension, unparseable UUID, or extra dots that
     * leave a non-UUID id text); a key that does not parse is skipped, never deleted, and never
     * passed to `findMissingExportIds`.
     */
    private fun parseExportId(storageKey: String): UUID? {
        if (!storageKey.startsWith("exports/")) return null
        val fileName = storageKey.removePrefix("exports/")
        val idText = fileName.substringBeforeLast('.', "")
        val extension = fileName.substringAfterLast('.', "")
        return if (idText.isEmpty() || extension.isEmpty()) null
        else runCatching { UUID.fromString(idText) }.getOrNull()
    }
}
