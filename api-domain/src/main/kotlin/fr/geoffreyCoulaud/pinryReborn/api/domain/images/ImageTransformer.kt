package fr.geoffreyCoulaud.pinryReborn.api.domain.images

import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import java.io.InputStream

interface ImageTransformer {
    /**
     * Render [source] to a fresh temp WebP file per [spec], returning it as a [StagedFile].
     * Never upscales beyond the source's native size (the caller passes an already-clamped
     * shortest side). The caller owns the returned temp file (promote or discard it).
     */
    fun render(source: InputStream, spec: RenditionSpec): StagedFile
}
