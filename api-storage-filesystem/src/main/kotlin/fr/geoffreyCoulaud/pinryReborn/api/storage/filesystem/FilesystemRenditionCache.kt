package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.StagedFile
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * [RenditionCache] adapter backed by the local filesystem, under `<dataDir>/cache/<imageId>/`.
 *
 * Not `@ApplicationScoped` (a `String` ctor param is unresolvable by ARC); a producer in the
 * composition root builds it, mirroring `FilesystemImageStore`.
 */
class FilesystemRenditionCache(dataDir: String) : RenditionCache {
    private val paths = DataDirPaths(dataDir)

    private fun keyPath(imageId: UUID, key: String): Path = paths.resolveWithinRoot("cache/$imageId/$key")

    override fun openStream(imageId: UUID, key: String): InputStream? {
        val path = keyPath(imageId, key)
        return if (Files.exists(path)) Files.newInputStream(path) else null
    }

    override fun store(imageId: UUID, key: String, staged: StagedFile) {
        val dest = keyPath(imageId, key)
        Files.createDirectories(dest.parent)
        paths.atomicMove(Path.of(staged.path), dest)
    }

    override fun evictImage(imageId: UUID) {
        val dir = paths.resolveWithinRoot("cache/$imageId")
        if (!Files.exists(dir)) return
        // Delete depth-first (children before parents) so the directory tree can be removed.
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
