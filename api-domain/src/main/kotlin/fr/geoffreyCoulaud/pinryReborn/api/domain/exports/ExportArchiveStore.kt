package fr.geoffreyCoulaud.pinryReborn.api.domain.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import java.io.InputStream
import java.time.Instant

/** The container format a given [ExportArchiveStore] produces. */
data class ArchiveFormat(val mediaType: String, val fileExtension: String)

/** Size and content hash of one entry written to an [ArchiveSink], measured uncompressed. */
data class ArchiveEntryDigest(val path: String, val byteSize: Long, val sha256: String)

/** Writable target for the content of one export archive, passed to the [ExportArchiveStore.stage] block. */
interface ArchiveSink {
    fun putTextEntry(name: String, text: String): ArchiveEntryDigest
    fun putJsonEntry(name: String, value: Any): ArchiveEntryDigest
    fun putJsonLinesEntry(name: String, values: Sequence<Any>): ArchiveEntryDigest
    fun putBinaryEntry(name: String, bytes: InputStream): ArchiveEntryDigest
}

/**
 * Produces, stores and retires export archives.
 *
 * Mirrors [fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore]: stage into a temp file,
 * promote by atomic rename, so a truncated archive is never reachable.
 */
interface ExportArchiveStore {
    val format: ArchiveFormat
    fun hasFreeSpace(requiredBytes: Long): Boolean
    fun stage(block: (ArchiveSink) -> Unit): StagedFile
    fun promote(staged: StagedFile, storageKey: String)
    fun openStream(storageKey: String, skipBytes: Long = 0): InputStream
    fun delete(storageKey: String)
    fun discard(staged: StagedFile)
    fun discardOrphanedStagedFiles(olderThan: Instant): Int
}
