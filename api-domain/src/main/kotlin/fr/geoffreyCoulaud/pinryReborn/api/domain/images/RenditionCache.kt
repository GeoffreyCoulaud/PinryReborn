package fr.geoffreyCoulaud.pinryReborn.api.domain.images

import java.io.InputStream
import java.util.UUID

/** Disposable, regenerable cache of image renditions, keyed by (canonical image id, key). */
interface RenditionCache {
    /** Open a read stream for a cached rendition, or null on a miss. */
    fun openStream(imageId: UUID, key: String): InputStream?

    /**
     * Atomically move a staged temp file into the cache at (imageId, key).
     *
     * Takes ownership of [staged]: on success the temp is moved into the cache, on failure it is
     * discarded before the error propagates. Either way the caller must not discard it afterwards
     * (nor rely on it still existing).
     */
    fun store(imageId: UUID, key: String, staged: StagedFile)

    /** Delete the whole cache subtree for an image (idempotent; a no-op when absent). */
    fun evictImage(imageId: UUID)
}
