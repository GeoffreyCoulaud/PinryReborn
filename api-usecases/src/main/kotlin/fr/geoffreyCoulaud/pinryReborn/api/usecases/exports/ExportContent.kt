package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveEntryDigest
import java.time.Instant
import java.util.UUID

/**
 * Content types written into a user data export archive
 * (spec `docs/specs/2026-07-22-user-data-export.md` §4). Field names are the published archive
 * format: renaming a property here renames a file's on-disk shape, so they are copied from the spec
 * verbatim, not "improved". Nullability mirrors the corresponding domain entity's; per spec §4,
 * "absent values are `null`, never omitted" (see [ExportContentGoldenJsonTest] for the pinned proof).
 *
 * Deliberately plain Kotlin, with no Jackson import: `api-usecases` carries no Jackson dependency on
 * its main classpath (Jackson is adapter-only, per the plan's tech stack). The adapter
 * (`FilesystemZipExportArchiveStore`, in `api-storage-filesystem`) supplies its own, explicitly
 * configured `ObjectMapper` and serializes these types through plain JavaBean getter introspection
 * (there is no `jackson-module-kotlin` anywhere in this codebase).
 */

/** `manifest.json`'s `generator` object: identifies what produced the archive. */
internal data class ExportGenerator(val name: String, val version: String)

/**
 * A lightweight `{ id, name }` reference. Reused for `manifest.json`'s `user` and for a pin's `tags`
 * and `boards` memberships in `pins.jsonl` -- the full record lives in `user.json`/`tags.jsonl`/
 * `boards.jsonl` respectively, so a membership only needs enough to point at it.
 */
internal data class ExportedRef(val id: UUID, val name: String)

/** One `manifest.json` `excluded` entry: what was left out of the archive, and why. */
internal data class ExportExclusion(val what: String, val why: String)

/** `user.json`: the exporting user's own account, in full (unlike the [ExportedRef] used elsewhere). */
internal data class ExportedUser(val id: UUID, val name: String, val createdAt: Instant?)

/** One `tags.jsonl` line. */
internal data class ExportedTag(val id: UUID, val name: String, val createdAt: Instant?)

/**
 * One `boards.jsonl` line. Active *and* recycled boards are written; `deletedAt` carries the state, so
 * a pin's `boards` membership (an [ExportedRef]) never needs to be cross-checked against this file to
 * tell whether the board still exists.
 */
internal data class ExportedBoard(
    val id: UUID,
    val name: String,
    val description: String,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val deletedAt: Instant?,
)

/**
 * A pin's `image` object. `path` is the archive-relative entry path (`images/<imageId>.<ext>`, from
 * [ExportImageExtension]), never a bare id, so the file only needs `path` to be located inside the
 * archive. `sha256` is [fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image.contentHash] under
 * its published name.
 */
internal data class ExportedImage(
    val id: UUID,
    val path: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val animated: Boolean,
    val byteSize: Long,
    val sha256: String,
    val createdAt: Instant,
)

/**
 * One `pins.jsonl` line. `image` is `null` when the pin has no image **or** when its bytes could not
 * be written (spec §4); `boards` lists memberships regardless of the board's state, since
 * `boards.jsonl` (via `ExportedBoard.deletedAt`) is the authority on whether a board is recycled.
 */
internal data class ExportedPin(
    val id: UUID,
    val description: String,
    val sourceContextUrl: String,
    val sourceMediaUrl: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val deletedAt: Instant?,
    val tags: List<ExportedRef>,
    val boards: List<ExportedRef>,
    val image: ExportedImage?,
)

/** `manifest.json`'s `counts` object: incremented while writing, never re-derived by re-iterating. */
internal data class ExportCounts(val pins: Int, val boards: Int, val tags: Int, val images: Int)

/**
 * `manifest.json` in full, written last, once every other entry's [ArchiveEntryDigest] is known.
 * `entries` reuses the domain [ArchiveEntryDigest] directly: its `path`/`byteSize`/`sha256` fields are
 * exactly `manifest.json`'s per-entry shape, and [fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveSink]
 * already returns one per write, so a duplicate type here would only be able to drift from it.
 */
internal data class ExportManifest(
    val formatVersion: Int,
    val generator: ExportGenerator,
    val exportId: UUID,
    val createdAt: Instant,
    val expiresAt: Instant,
    val user: ExportedRef,
    val counts: ExportCounts,
    val entries: List<ArchiveEntryDigest>,
    val excluded: List<ExportExclusion>,
)
