package fr.geoffreyCoulaud.pinryReborn.api.domain.images

import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import java.io.InputStream

interface ImageStore {
    /**
     * Stream [source] into a fresh temp file under the data dir, aborting past [maxBytes]
     * (throws ImageTooLargeException), computing byteSize + SHA-256 in one pass.
     */
    fun stage(source: InputStream, maxBytes: Long): StagedFile

    /**
     * Move a staged temp file to [storageKey] (a fresh path).
     */
    fun promote(staged: StagedFile, storageKey: String)

    /**
     * Open a read stream for a stored key.
     */
    fun openStream(storageKey: String): InputStream

    /**
     * Delete [storageKey] if present (idempotent).
     */
    fun delete(storageKey: String)

    /**
     * Delete a staged temp file (cleanup on failure; idempotent).
     */
    fun discard(staged: StagedFile)
}
