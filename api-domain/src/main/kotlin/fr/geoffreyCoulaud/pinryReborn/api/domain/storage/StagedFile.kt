package fr.geoffreyCoulaud.pinryReborn.api.domain.storage

/**
 * Opaque local staging reference plus measured size and content hash.
 *
 * Lives outside `domain.images` because staging is a storage concern, not an imaging one: image
 * bytes and export archives are both written this way (stage into a temp file while measuring, then
 * promote by atomic rename).
 */
data class StagedFile(val path: String, val byteSize: Long, val contentHash: String)
