package fr.geoffreyCoulaud.pinryReborn.api.domain.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import java.io.InputStream
import java.time.Instant
import java.util.UUID

/**
 * Receives an archive upload chunk by chunk, then stores and retires it. Mirrors
 * `ExportArchiveStore`: promote by atomic rename, so a truncated archive is never reachable.
 */
interface ImportArchiveStore {
    fun hasFreeSpace(requiredBytes: Long): Boolean

    /**
     * Append [bytes] at [offset] and return the new length. Refuses an [offset] that is not the
     * current length, and a total past [maxTotalBytes], leaving the length untouched so a client resumes.
     */
    fun appendChunk(importId: UUID, offset: Long, bytes: InputStream, maxTotalBytes: Long): Long

    /** Close the upload, fsync it and digest it, giving the same [StagedFile] the export staging does. */
    fun finishUpload(importId: UUID): StagedFile

    fun promote(staged: StagedFile, storageKey: String)

    fun open(storageKey: String): ArchiveSource

    fun delete(storageKey: String)

    /** Drop an upload still in flight, whatever it has received. */
    fun discardPartialUpload(importId: UUID)

    /** Drop staged uploads older than [olderThan], returning how many went, and never a promoted one. */
    fun discardOrphanedStagedFiles(olderThan: Instant): Int

    /**
     * Enumerate every promoted archive key on disk, loaning a lazy [Sequence] to [block], which owns
     * it only until it returns. Never yields an upload in flight, which the sweep would reclaim.
     */
    fun forEachStorageKeyOnDisk(block: (Sequence<String>) -> Unit)
}
