package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveSink
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipOutputStream
import kotlin.streams.asSequence

/**
 * [ExportArchiveStore] adapter backed by the local filesystem, producing ZIP archives.
 *
 * Mirrors [FilesystemImageStore]: bytes are staged under `<dataDir>/tmp/`, measured (size +
 * SHA-256) in a single streaming pass, then promoted (moved) to their final
 * `<dataDir>/<storageKey>` location.
 *
 * [dataDir] is a plain string (not injected) for the same reason as [FilesystemImageStore]: this
 * class stays framework-light and unit-testable with a temp directory. CDI wiring of the actual
 * data directory is done by a producer elsewhere.
 */
class FilesystemZipExportArchiveStore(private val dataDir: String) : ExportArchiveStore {

    override val format = ArchiveFormat(mediaType = "application/zip", fileExtension = "zip")

    private val mapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private val paths = DataDirPaths(dataDir)
    private val tmpDir: Path get() = Path.of(dataDir).resolve("tmp")

    override fun hasFreeSpace(requiredBytes: Long): Boolean {
        Files.createDirectories(tmpDir)
        return Files.getFileStore(tmpDir).usableSpace >= requiredBytes
    }

    // Cleanup-on-failure genuinely has to catch everything here, mirroring
    // FilesystemImageStore.stage: the writer block can throw anything (a domain guard, a broken
    // source stream, a write/fsync failure under disk pressure), and "no temp file on error" is
    // this store's guarantee regardless of the failure's shape. The catch-and-rethrow dispatches
    // via the JVM exception table, not a conditional jump, so it adds no uncovered branch.
    @Suppress("TooGenericExceptionCaught")
    override fun stage(block: (ArchiveSink) -> Unit): StagedFile {
        Files.createDirectories(tmpDir)
        val tempPath = Files.createTempFile(tmpDir, TEMP_PREFIX, ".tmp")
        try {
            val fileOut = FileOutputStream(tempPath.toFile())
            val counting = CountingDigestOutputStream(BufferedOutputStream(fileOut))
            try {
                // CountingDigestOutputStream.close() deliberately only flushes (a ZIP entry stream
                // must outlive the wrapper), so the underlying FileOutputStream is closed here, in
                // this finally, once the ZipOutputStream itself has been closed above.
                ZipOutputStream(counting).use { zip -> block(ZipArchiveSink(zip, mapper)) }
                fileOut.flush()
                fileOut.channel.force(true)
            } finally {
                fileOut.close()
            }
            return StagedFile(tempPath.toString(), counting.count, counting.digestHex())
        } catch (error: Throwable) {
            Files.deleteIfExists(tempPath)
            throw error
        }
    }

    override fun promote(staged: StagedFile, storageKey: String) {
        val dest = paths.resolveWithinRoot(storageKey)
        Files.createDirectories(dest.parent)
        paths.atomicMove(Path.of(staged.path), dest)
    }

    override fun openStream(storageKey: String, skipBytes: Long): InputStream {
        val channel = Files.newByteChannel(paths.resolveWithinRoot(storageKey))
        channel.position(skipBytes)
        return Channels.newInputStream(channel)
    }

    override fun delete(storageKey: String) {
        Files.deleteIfExists(paths.resolveWithinRoot(storageKey))
    }

    override fun discard(staged: StagedFile) {
        Files.deleteIfExists(Path.of(staged.path))
    }

    override fun discardOrphanedStagedFiles(olderThan: Instant): Int {
        if (!Files.isDirectory(tmpDir)) return 0
        return Files.list(tmpDir).use { stream ->
            stream
                .filter { it.fileName.toString().startsWith(TEMP_PREFIX) }
                .filter { Files.getLastModifiedTime(it).toInstant().isBefore(olderThan) }
                .toList()
        }.count { Files.deleteIfExists(it) }
    }

    override fun forEachStorageKeyOnDisk(block: (Sequence<String>) -> Unit) {
        // List ONLY <dataDir>/exports/, never the dataDir root: the root also holds `tmp/`
        // staged files, which are not promoted archives and must never be swept here.
        val exportsDir = paths.resolveWithinRoot("exports")
        // A fresh install has no exports/ yet: Files.list would throw NoSuchFileException, which the
        // periodic sweep would log as a failure every tick. Run the block once on an empty sequence
        // instead, preserving the loan contract (the block always runs exactly once). Mirrors
        // discardOrphanedStagedFiles above.
        if (!Files.isDirectory(exportsDir)) {
            block(emptySequence())
            return
        }
        Files.list(exportsDir).use { stream ->
            block(stream.asSequence().filter { Files.isRegularFile(it) }.map { "exports/${it.fileName}" })
        }
    }

    private companion object {
        const val TEMP_PREFIX = "export-"
    }
}
