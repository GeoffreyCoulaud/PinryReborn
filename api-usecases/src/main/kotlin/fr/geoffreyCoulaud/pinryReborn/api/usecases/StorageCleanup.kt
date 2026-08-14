package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID

/**
 * Shared best-effort body for storage cleanup: a throw from [block] is logged at WARN and
 * swallowed, never propagated to the caller. A business operation that already succeeded must
 * not fail because of a file cleanup; the periodic garbage collection is the ultimate guarantor of
 * residue (see docs/adr/0003-periodic-gc-and-best-effort-cleanup.md).
 */
object StorageCleanup {
    private val logger = KotlinLogging.logger {}

    internal fun runQuietly(what: String, block: () -> Unit) {
        runCatching(block).onFailure { logger.warn(it) { "storage cleanup failed: $what" } }
    }
}

/** Best-effort [ImageStore.delete]: logs WARN and swallows on failure. */
fun ImageStore.deleteQuietly(storageKey: String) =
    StorageCleanup.runQuietly("image $storageKey") { delete(storageKey) }

/** Best-effort [ImageStore.discard]: logs WARN and swallows on failure. */
fun ImageStore.discardQuietly(staged: StagedFile) =
    StorageCleanup.runQuietly("staged ${staged.path}") { discard(staged) }

/** Best-effort [RenditionCache.evictImage]: logs WARN and swallows on failure. */
fun RenditionCache.evictImageQuietly(imageId: UUID) =
    StorageCleanup.runQuietly("renditions $imageId") { evictImage(imageId) }

/** Best-effort [ExportArchiveStore.delete]: logs WARN and swallows on failure. */
fun ExportArchiveStore.deleteQuietly(storageKey: String) =
    StorageCleanup.runQuietly("export $storageKey") { delete(storageKey) }

/** Best-effort [ImportArchiveStore.delete]: logs WARN and swallows on failure. */
fun ImportArchiveStore.deleteQuietly(storageKey: String) =
    StorageCleanup.runQuietly("import $storageKey") { delete(storageKey) }
