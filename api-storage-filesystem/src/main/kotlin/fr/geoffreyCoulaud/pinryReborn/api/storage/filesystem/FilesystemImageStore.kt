package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooLargeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.StagedFile
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

/**
 * [ImageStore] adapter backed by the local filesystem.
 *
 * Bytes are staged under `<dataDir>/tmp/`, measured (size + SHA-256) in a single streaming
 * pass, then promoted (moved) to their final `<dataDir>/<storageKey>` location.
 *
 * [dataDir] is a plain string (not injected) so this class stays framework-light and
 * unit-testable with a temp directory; CDI wiring of the actual data directory is done by
 * a producer elsewhere.
 *
 * Deliberately NOT `@ApplicationScoped`: ARC cannot satisfy a plain `String` constructor
 * parameter on its own, so this class is instantiated exclusively by
 * `ImageAdapterProducers` (`api-presentation-quarkus`), which resolves `dataDir` from
 * `ImagesConfig`. Adding `@ApplicationScoped` back here alongside that `@Produces` method
 * would create an ambiguous `ImageStore` bean resolution.
 */
class FilesystemImageStore(private val dataDir: String) : ImageStore {

    private companion object {
        private const val STREAM_BUFFER_SIZE = 8192
        private val HEX = HexFormat.of()
    }

    private val root: Path get() = Path.of(dataDir)
    private val tmpDir: Path get() = root.resolve("tmp")

    // Cleanup-on-failure genuinely has to catch everything: the ImageTooLargeException guard,
    // an IOException from a broken source stream, a write/fsync failure under disk pressure, or
    // any other Throwable mid-stage must all leave no partial temp behind ("no temp file on
    // error" is this store's guarantee). A `finally` + success-flag was rejected: the Kotlin
    // compiler duplicates the finally block, so the normal-completion copy's `if (!success)`
    // only ever sees success == true and stays partly covered, breaking the 100%-branch gate.
    // The catch-and-rethrow below dispatches via the JVM exception table (not a conditional
    // jump), so it adds no uncovered branch. Hence the deliberate broad catch.
    @Suppress("TooGenericExceptionCaught")
    override fun stage(source: InputStream, maxBytes: Long): StagedFile {
        Files.createDirectories(tmpDir)
        val tempPath = Files.createTempFile(tmpDir, "stage-", ".tmp")
        try {
            val (byteSize, digestBytes) = writeAndDigest(source, tempPath, maxBytes)
            return StagedFile(tempPath.toString(), byteSize, HEX.formatHex(digestBytes))
        } catch (error: Throwable) {
            Files.deleteIfExists(tempPath)
            throw error
        }
    }

    override fun promote(staged: StagedFile, storageKey: String) {
        val dest = resolveWithinRoot(storageKey)
        Files.createDirectories(dest.parent)
        move(Path.of(staged.path), dest)
    }

    override fun openStream(storageKey: String): InputStream = Files.newInputStream(resolveWithinRoot(storageKey))

    override fun delete(storageKey: String) {
        Files.deleteIfExists(resolveWithinRoot(storageKey))
    }

    override fun discard(staged: StagedFile) {
        Files.deleteIfExists(Path.of(staged.path))
    }

    /**
     * Streams [source] into [tempPath] while updating a SHA-256 digest and counting bytes,
     * aborting with [ImageTooLargeException] as soon as the running count exceeds [maxBytes].
     * Fsyncs the temp file before returning so a promote never observes a partially-flushed
     * file.
     */
    private fun writeAndDigest(source: InputStream, tempPath: Path, maxBytes: Long): Pair<Long, ByteArray> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        var byteSize = 0L
        FileOutputStream(tempPath.toFile()).use { out ->
            var read = source.read(buffer)
            while (read >= 0) {
                byteSize += read
                if (byteSize > maxBytes) {
                    throw ImageTooLargeException("Stream exceeded the $maxBytes byte limit")
                }
                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
                read = source.read(buffer)
            }
            out.flush()
            out.channel.force(true)
        }
        return byteSize to digest.digest()
    }

    /**
     * Moves [source] to [dest], preferring an atomic move and falling back to a plain move if
     * the filesystem cannot provide atomicity for this pair of paths. Correctness never
     * depends on atomicity: [source] and [dest] are both resolved under the same data
     * directory, so the atomic path is expected to always succeed in practice (single file
     * store per deployment).
     *
     * Coverage note: the [AtomicMoveNotSupportedException] fallback line is not exercised by
     * any test (source and dest always share a filesystem here) and shows as uncovered in the
     * Kover *line* report. It does not need a dedicated test to satisfy the 100%-branch gate:
     * a try/catch dispatches via the JVM exception table, not a conditional-jump instruction,
     * so Kover's *branch* metric (the gated one) does not count the handler as a branch at
     * all. Forcing this line with `mockkStatic(java.nio.file.Files::class)` was considered and
     * rejected: static mocking of JDK classes is documented elsewhere in this codebase
     * (`EbeanDatabaseProducerTest`) as deadlocking the test JVM.
     */
    private fun move(source: Path, dest: Path) {
        try {
            Files.move(source, dest, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, dest)
        }
    }

    /**
     * Resolves [storageKey] under [root], rejecting anything that escapes the data directory as
     * defence in depth. Normalising then checking containment covers both `..` traversal and an
     * absolute [storageKey] (which `Path.resolve` would otherwise return verbatim, outside the
     * root). Storage keys are server-generated (never taken verbatim from user input), so this
     * guard is a backstop rather than the primary safety mechanism.
     */
    private fun resolveWithinRoot(storageKey: String): Path {
        val normalizedRoot = root.normalize()
        val resolved = normalizedRoot.resolve(storageKey).normalize()
        require(resolved.startsWith(normalizedRoot)) { "Illegal storage key: $storageKey" }
        return resolved
    }
}
