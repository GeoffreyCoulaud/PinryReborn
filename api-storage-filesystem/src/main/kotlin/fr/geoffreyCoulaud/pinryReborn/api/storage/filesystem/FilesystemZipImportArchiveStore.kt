package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveSource
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveTooLargeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportChunkOffsetMismatchException
import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.streams.asSequence

/**
 * [ImportArchiveStore] over the filesystem, [FilesystemZipExportArchiveStore] the other way round: an
 * upload accumulates under `tmp/`, is measured in one pass, then promoted by atomic rename.
 */
@Suppress("TooManyFunctions") // Every port method plus its helpers: one cohesive adapter, never split.
class FilesystemZipImportArchiveStore(
    private val dataDir: String,
    private val maxLineBytes: Int,
) : ImportArchiveStore {
    private val paths = DataDirPaths(dataDir)
    private val tmpDir: Path get() = Path.of(dataDir).resolve("tmp")

    /**
     * Reader only, and never handed to a writer. The Kotlin module is what makes a missing or null JSON
     * field a parse failure instead of a null sitting inside a non-nullable property.
     */
    private val mapper: ObjectMapper =
        ObjectMapper(
            JsonFactory
                .builder()
                .streamReadConstraints(
                    // Explicit, since the defaults bound a single string and leave the document's shape
                    // open; the per-read byte bounds below are what cap its length.
                    StreamReadConstraints
                        .builder()
                        .maxNestingDepth(MAX_NESTING_DEPTH)
                        .maxStringLength(MAX_STRING_LENGTH)
                        .maxNameLength(MAX_NAME_LENGTH)
                        .maxNumberLength(MAX_NUMBER_LENGTH)
                        .build(),
                ).build(),
        ).registerModule(JavaTimeModule())
            .registerKotlinModule()

    override fun hasFreeSpace(requiredBytes: Long): Boolean {
        Files.createDirectories(tmpDir)
        return Files.getFileStore(tmpDir).usableSpace >= requiredBytes
    }

    override fun appendChunk(
        importId: UUID,
        offset: Long,
        bytes: InputStream,
        maxTotalBytes: Long,
    ): Long {
        Files.createDirectories(tmpDir)
        val path = uploadPath(importId)
        val currentLength = currentLength(path)
        if (offset != currentLength) throw ImportChunkOffsetMismatchException(currentLength = currentLength)
        return FileChannel.open(path, CREATE, WRITE).use { channel ->
            channel.position(currentLength)
            append(bytes, channel, currentLength, maxTotalBytes)
        }
    }

    /** Writes block by block, so an overrun is refused before the whole chunk is on disk. */
    private fun append(
        source: InputStream,
        channel: FileChannel,
        currentLength: Long,
        maxTotalBytes: Long,
    ): Long {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var length = currentLength
        while (true) {
            val read = source.read(buffer)
            if (read < 0) return length
            if (length + read > maxTotalBytes) {
                // Back to what the client already has, so it resumes rather than restarts.
                channel.truncate(currentLength)
                throw ImportArchiveTooLargeException(maxTotalBytes = maxTotalBytes)
            }
            channel.write(ByteBuffer.wrap(buffer, 0, read))
            length += read
        }
    }

    override fun finishUpload(importId: UUID): StagedFile {
        val path = uploadPath(importId)
        FileChannel.open(path, WRITE).use { it.force(true) }
        val counting = CountingDigestOutputStream(OutputStream.nullOutputStream())
        Files.newInputStream(path).use { it.copyTo(counting) }
        return StagedFile(path.toString(), counting.count, counting.digestHex())
    }

    override fun promote(
        staged: StagedFile,
        storageKey: String,
    ) {
        val destination = paths.resolveWithinRoot(storageKey)
        Files.createDirectories(destination.parent)
        paths.atomicMove(Path.of(staged.path), destination)
    }

    override fun open(storageKey: String): ArchiveSource =
        ZipArchiveSource(
            zip = ZipFile(paths.resolveWithinRoot(storageKey).toFile()),
            mapper = mapper,
            maxLineBytes = maxLineBytes,
        )

    override fun delete(storageKey: String) {
        Files.deleteIfExists(paths.resolveWithinRoot(storageKey))
    }

    override fun discardPartialUpload(importId: UUID) {
        Files.deleteIfExists(uploadPath(importId))
    }

    override fun discardOrphanedStagedFiles(olderThan: Instant): Int {
        if (!Files.isDirectory(tmpDir)) return 0
        return Files
            .list(tmpDir)
            .use { stream ->
                stream
                    .filter { it.fileName.toString().startsWith(UPLOAD_PREFIX) }
                    .filter { Files.getLastModifiedTime(it).toInstant().isBefore(olderThan) }
                    .toList()
            }.count { Files.deleteIfExists(it) }
    }

    override fun forEachStorageKeyOnDisk(block: (Sequence<String>) -> Unit) {
        // Only `<dataDir>/imports/`, never the root, which also holds the uploads still in flight the
        // sweep would otherwise reclaim from under a client. A fresh install has no such directory yet,
        // and the loan contract says the block runs exactly once either way.
        val importsDir = paths.resolveWithinRoot(IMPORTS_DIR)
        if (!Files.isDirectory(importsDir)) {
            block(emptySequence())
            return
        }
        Files.list(importsDir).use { stream ->
            block(stream.asSequence().filter { Files.isRegularFile(it) }.map { "$IMPORTS_DIR/${it.fileName}" })
        }
    }

    private fun uploadPath(importId: UUID): Path = tmpDir.resolve("$UPLOAD_PREFIX$importId.part")

    private fun currentLength(path: Path): Long = if (Files.exists(path)) Files.size(path) else 0

    private companion object {
        const val UPLOAD_PREFIX = "import-"
        const val IMPORTS_DIR = "imports"
        const val COPY_BUFFER_BYTES = 8 * 1024
        const val MAX_NESTING_DEPTH = 32
        const val MAX_STRING_LENGTH = 1 * 1024 * 1024
        const val MAX_NAME_LENGTH = 256
        const val MAX_NUMBER_LENGTH = 100
    }
}
