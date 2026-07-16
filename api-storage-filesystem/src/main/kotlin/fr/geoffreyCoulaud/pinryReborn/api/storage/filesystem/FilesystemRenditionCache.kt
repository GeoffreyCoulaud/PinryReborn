package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.StagedFile
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
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
        // keyPath stays outside the try so a traversal key still surfaces as an
        // IllegalArgumentException instead of being swallowed into a miss.
        val path = keyPath(imageId, key)
        // Opening straight away (rather than exists() then open) is race-free: a concurrent evict
        // between the two calls would otherwise turn a miss (regenerate) into a NoSuchFileException
        // (a 500). It also drops a stat call and a branch.
        return try {
            Files.newInputStream(path)
        } catch (_: NoSuchFileException) {
            null
        }
    }

    // Storing takes ownership of the staged temp, so cleanup-on-failure genuinely has to catch
    // everything: a full or read-only data dir (createDirectories / the move raise IOException), or
    // any other Throwable mid-store, must still leave no orphan behind. Otherwise the cache never
    // populates, every later GET re-renders, and each one leaks another temp into java.io.tmpdir
    // (frequently a tmpfs, i.e. RAM). Mirrors FilesystemImageStore.stage: the catch-and-rethrow
    // dispatches via the JVM exception table (not a conditional jump), so it adds no uncovered
    // Kover branch. Hence the deliberate broad catch.
    @Suppress("TooGenericExceptionCaught")
    override fun store(imageId: UUID, key: String, staged: StagedFile) {
        try {
            val dest = keyPath(imageId, key)
            Files.createDirectories(dest.parent)
            paths.atomicMove(Path.of(staged.path), dest)
        } catch (error: Throwable) {
            Files.deleteIfExists(Path.of(staged.path))
            throw error
        }
    }

    override fun evictImage(imageId: UUID) {
        val dir = paths.resolveWithinRoot("cache/$imageId")
        try {
            // Delete depth-first (children before parents) so the directory tree can be removed.
            Files.walk(dir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        } catch (_: NoSuchFileException) {
            // Already gone: the subtree was never created, or a concurrent evict won the race.
            // Catching beats an exists() pre-check, which only narrows the same window.
        }
    }
}
