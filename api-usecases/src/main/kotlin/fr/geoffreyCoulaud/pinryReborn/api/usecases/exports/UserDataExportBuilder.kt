package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveEntryDigest
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveSink
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import java.time.Duration

/**
 * Builds a user data export archive (spec `docs/specs/2026-07-22-user-data-export.md` §3, §4, §8):
 * walks a user's pins, boards, tags and images into an [ExportArchiveStore], then runs the worker's
 * `build` state machine that promotes the staged file and publishes the row.
 *
 * Deliberately not `@ApplicationScoped` yet, same precedent as [UserDataExportRequester]:
 * `applicationVersion`/`pageSize`/`retention`/`minimumFreeBytes` have no CDI producer until the
 * wiring task (`ExportProducers`).
 *
 * `@Suppress("TooManyFunctions")`: the build/write pipeline is intentionally split into many small,
 * single-purpose private helpers to keep each one under the method-length, return-count and
 * throws-count limits (mirrors `PinRepositoryInterface`'s precedent for the same trade-off).
 */
@Suppress("LongParameterList", "TooManyFunctions")
class UserDataExportBuilder(
    private val exportRepository: UserDataExportRepositoryInterface,
    private val userRepository: UserRepositoryInterface,
    private val pinRepository: PinRepositoryInterface,
    private val imageRepository: ImageRepositoryInterface,
    private val boardRepository: BoardRepositoryInterface,
    private val tagRepository: TagRepositoryInterface,
    private val imageStore: ImageStore,
    private val archiveStore: ExportArchiveStore,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
    private val applicationVersion: String,
    private val pageSize: Int,
    private val retention: Duration,
    private val minimumFreeBytes: Long,
) {

    /**
     * Writes every archive entry for [export]/[user] into a freshly staged file, in the load-bearing
     * order from spec §3: `README.md`, `user.json`, `boards.jsonl`, `tags.jsonl`, the image entries
     * (first pin walk), `pins.jsonl` (second pin walk, referencing only images actually written), and
     * `manifest.json` last. [renewLease] is threaded down to the pin walks so a long build keeps its
     * task lease alive (spec §15).
     */
    internal fun stageArchive(export: UserDataExport, user: User, renewLease: () -> Unit): StagedFile {
        val createdAt = clock.now()
        val header = ExportManifest(
            formatVersion = export.formatVersion,
            generator = ExportGenerator(GENERATOR_NAME, applicationVersion),
            exportId = export.id,
            createdAt = createdAt,
            expiresAt = createdAt.plus(retention),
            user = ExportedRef(user.id, user.name),
            counts = ExportCounts(pins = 0, boards = 0, tags = 0, images = 0),
            entries = emptyList(),
            excluded = EXCLUSIONS,
        )
        return archiveStore.stage { sink -> writeArchive(sink, header, user, renewLease) }
    }

    private fun writeArchive(sink: ArchiveSink, header: ExportManifest, user: User, renewLease: () -> Unit) {
        val entries = mutableListOf<ArchiveEntryDigest>()
        entries += sink.putTextEntry("README.md", ExportReadme.render(header))
        entries += sink.putJsonEntry("user.json", ExportedUser(user.id, user.name, user.createdAt))

        val boards = boardRepository.findActiveBoardsForUser(user) + boardRepository.findRecycledBoardsForUser(user)
        val boardCount = writeCollection(sink, entries, "boards.jsonl", boards) { board ->
            ExportedBoard(
                board.id, board.name, board.description, board.createdAt, board.updatedAt, board.softDeletedAt,
            )
        }
        val tagCount = writeCollection(sink, entries, "tags.jsonl", tagRepository.findAllTagsForUser(user)) { tag ->
            ExportedTag(tag.id, tag.name, tag.createdAt)
        }

        val writtenImagePaths = mutableSetOf<String>()
        val imageCount = writeImages(sink, entries, user, renewLease, writtenImagePaths)
        val pinCount = writePins(sink, entries, user, renewLease, writtenImagePaths)

        val manifest = header.copy(
            counts = ExportCounts(pins = pinCount, boards = boardCount, tags = tagCount, images = imageCount),
            entries = entries.toList(),
        )
        sink.putJsonEntry("manifest.json", manifest)
    }

    /** Writes one JSONL entry from [items], counting them as they are mapped and consumed by the sink. */
    private fun <T> writeCollection(
        sink: ArchiveSink,
        entries: MutableList<ArchiveEntryDigest>,
        name: String,
        items: List<T>,
        toExported: (T) -> Any,
    ): Int {
        var count = 0
        val values = items.asSequence().map(toExported).onEach { count++ }
        entries += sink.putJsonLinesEntry(name, values)
        return count
    }

    /**
     * Walk 1: writes every pin's image, BEFORE `pins.jsonl` is opened (a ZIP holds one open entry at
     * a time). Records each written path so walk 2 can tell a dangling reference from a real one.
     */
    private fun writeImages(
        sink: ArchiveSink,
        entries: MutableList<ArchiveEntryDigest>,
        user: User,
        renewLease: () -> Unit,
        writtenImagePaths: MutableSet<String>,
    ): Int {
        var count = 0
        for (pin in allPins(user, renewLease)) {
            val image = imageRepository.findByPinId(pin.id) ?: continue
            val path = imagePath(image)
            entries += sink.putBinaryEntry(path, imageStore.openStream(image.storageKey))
            renewLease()
            writtenImagePaths += path
            count++
        }
        return count
    }

    /** Walk 2: re-reads each pin's image independently of walk 1, so the two walks can disagree. */
    private fun writePins(
        sink: ArchiveSink,
        entries: MutableList<ArchiveEntryDigest>,
        user: User,
        renewLease: () -> Unit,
        writtenImagePaths: Set<String>,
    ): Int {
        var count = 0
        val pins = allPins(user, renewLease)
            .map { pin -> exportedPin(pin, writtenImagePaths) }
            .onEach { count++ }
        entries += sink.putJsonLinesEntry("pins.jsonl", pins)
        return count
    }

    private fun exportedPin(pin: Pin, writtenImagePaths: Set<String>): ExportedPin = ExportedPin(
        id = pin.id,
        description = pin.description,
        sourceContextUrl = pin.sourceContextUrl,
        sourceMediaUrl = pin.sourceMediaUrl,
        createdAt = pin.createdAt,
        updatedAt = pin.updatedAt,
        deletedAt = pin.softDeletedAt,
        tags = pin.tags.map { tag -> ExportedRef(tag.id, tag.name) },
        boards = pinRepository.findBoardsForPinIncludingRecycled(pin.id)
            .map { board -> ExportedRef(board.id, board.name) },
        image = exportedImage(pin, writtenImagePaths),
    )

    /**
     * `null` when the pin has no image **or** when its bytes could not be written (spec §4): the
     * second condition is what makes a dangling reference structurally impossible, since a path only
     * survives here if walk 1 actually wrote it.
     */
    private fun exportedImage(pin: Pin, writtenImagePaths: Set<String>): ExportedImage? {
        val image = imageRepository.findByPinId(pin.id) ?: return null
        val path = imagePath(image)
        return if (path in writtenImagePaths) {
            ExportedImage(
                id = image.id,
                path = path,
                mimeType = image.mimeType,
                width = image.width,
                height = image.height,
                animated = image.animated,
                byteSize = image.byteSize,
                sha256 = image.contentHash,
                createdAt = image.createdAt,
            )
        } else {
            null
        }
    }

    private fun imagePath(image: Image): String =
        "images/${image.id}.${ExportImageExtension.forMimeType(image.mimeType)}"

    /** Active pins, then recycled pins: the set walked by both image and pin passes. */
    private fun allPins(user: User, renewLease: () -> Unit): Sequence<Pin> =
        pinSequence(user, recycled = false, renewLease) + pinSequence(user, recycled = true, renewLease)

    /**
     * Cursor-paginated pins for one state (active or recycled), renewing the task lease once per
     * fetched page -- called independently by each of the two walks in [allPins], so a slow build
     * renews on every page of every walk, not just once overall.
     */
    private fun pinSequence(user: User, recycled: Boolean, renewLease: () -> Unit) = sequence {
        var cursor: Cursor? = null
        do {
            val page = if (recycled) {
                pinRepository.findSoftDeletedPinsForUser(user, cursor, pageSize, PinSortStrategy.DELETED_AT_DESC)
            } else {
                pinRepository.findPinsForUser(user, cursor, pageSize, PinSortStrategy.CREATED_AT_DESC)
            }
            renewLease()
            yieldAll(page.items)
            cursor = page.nextCursor
        } while (cursor != null)
    }

    private companion object {
        const val GENERATOR_NAME = "pinry-reborn"
        val EXCLUSIONS = listOf(
            ExportExclusion("password hashes", "secrets; useless to you, dangerous if this archive leaks"),
            ExportExclusion("session tokens", "secrets; expired and meaningless outside this instance"),
            ExportExclusion("image renditions", "derived from the original bytes, regenerable"),
        )
    }
}
