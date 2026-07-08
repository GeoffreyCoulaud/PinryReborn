package fr.geoffreyCoulaud.pinryReborn.api.domain.images

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.ImageFormat

data class ProbeResult(val format: ImageFormat, val width: Int, val height: Int)

interface ImageProbe {
    /**
     * Validate + measure the staged file. Reject over [maxPixels]. Throws on unsupported/undecodable.
     */
    fun probe(staged: StagedFile, maxPixels: Long): ProbeResult
}
