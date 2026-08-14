package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import java.time.Instant

/**
 * What the importer reads out of a `formatVersion` 1 archive (spec section 4): only the fields it acts
 * on, so an archive identifier never reaches a row. Plain Kotlin, no Jackson: the adapter owns the mapper.
 */

/** `manifest.json`'s `counts`, read for progress display only and never for a decision. */
internal data class ImportedCounts(val pins: Int?)

internal data class ImportedManifest(val formatVersion: Int, val counts: ImportedCounts?)

/** One `tags.jsonl` line; its `id` is read and discarded, since identity is the name. */
internal data class ImportedTag(val name: String, val createdAt: Instant)

/** One `boards.jsonl` line. `deletedAt` carries the recycled state a created board is given. */
internal data class ImportedBoard(
    val name: String,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)

/** A pin's tag or board membership; the archive's `id` is dropped, since identity is the name. */
internal data class ImportedRef(val name: String)

/**
 * A pin's `image` object. `mimeType` and the dimensions are deliberately absent: the manifest is never
 * trusted for anything with a consequence, and `sha256` is read only to be compared and reported.
 */
internal data class ImportedImage(val path: String, val sha256: String)

/** One `pins.jsonl` line. A null [image] is a pin with no medium, which has no identity to import. */
internal data class ImportedPin(
    val description: String,
    val sourceContextUrl: String,
    val sourceMediaUrl: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val tags: List<ImportedRef>,
    val boards: List<ImportedRef>,
    val image: ImportedImage?,
)
