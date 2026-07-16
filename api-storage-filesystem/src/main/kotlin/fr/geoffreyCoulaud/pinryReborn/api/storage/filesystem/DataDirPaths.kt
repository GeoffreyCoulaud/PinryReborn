package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Shared filesystem primitives for the data-dir-backed adapters: path containment + atomic move. */
internal class DataDirPaths(dataDir: String) {
    private val root: Path = Path.of(dataDir).normalize()

    /**
     * Resolves [key] under the data dir, rejecting anything that escapes it (defence in depth;
     * keys are server-generated). Normalising then checking containment covers both `..`
     * traversal and an absolute [key].
     */
    fun resolveWithinRoot(key: String): Path {
        val resolved = root.resolve(key).normalize()
        require(resolved.startsWith(root)) { "Illegal storage key: $key" }
        return resolved
    }

    /**
     * Moves [source] to [dest], preferring an atomic move and falling back to a plain move when
     * the filesystem cannot provide atomicity. The fallback is a try/catch (JVM exception table,
     * not a conditional jump), so Kover's branch metric does not count it. Forcing this line with
     * `mockkStatic(java.nio.file.Files::class)` was considered and rejected: static mocking of JDK
     * classes is documented elsewhere in this codebase (`EbeanDatabaseProducerTest`) as deadlocking
     * the test JVM.
     */
    fun atomicMove(source: Path, dest: Path) {
        try {
            Files.move(source, dest, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, dest)
        }
    }
}
