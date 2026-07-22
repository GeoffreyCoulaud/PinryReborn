package fr.geoffreyCoulaud.pinryReborn.api.domain.images

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.ImageFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile

data class ProbeResult(val format: ImageFormat, val width: Int, val height: Int, val animated: Boolean)

interface ImageProbe {
    /**
     * Validate + measure the staged file. Reject over [maxPixels]. Throws on unsupported/undecodable.
     */
    fun probe(staged: StagedFile, maxPixels: Long): ProbeResult
}
