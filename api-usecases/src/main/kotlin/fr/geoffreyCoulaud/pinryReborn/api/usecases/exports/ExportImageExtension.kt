package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

/**
 * Maps an [fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image.mimeType] to the file extension
 * used for its `images/<imageId>.<ext>` entry (spec `docs/specs/2026-07-22-user-data-export.md` §4).
 * The MIME type comes from a server-side enum, never from client input, so no user-controlled string
 * ever reaches a ZIP entry path.
 */
internal object ExportImageExtension {
    fun forMimeType(mimeType: String): String = when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/avif" -> "avif"
        else -> "bin"
    }
}
