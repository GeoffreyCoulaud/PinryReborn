package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImportIssue
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.ImageFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageProbe
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ProbeResult
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveEntryUnreadableException
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveLine
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveSource
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportIssueRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.UUID.randomUUID

/** The ambient transaction the claim runs in; this suite owns no connection. */
internal class PassthroughTransactionRunner : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T = block()
}

internal data class TestLine<out T>(
    override val line: Int,
    override val value: T?,
    override val failure: String? = null,
) : ArchiveLine<T>

/**
 * An [ArchiveSource] over typed lines, so a runner test says what the archive holds. A [media] name
 * mapped to null is announced by [entryNames] and refused by [openEntry], the one shape they disagree on.
 */
internal data class FakeArchiveSource(
    val manifest: ImportedManifest?,
    val tags: List<ArchiveLine<ImportedTag>> = emptyList(),
    val boards: List<ArchiveLine<ImportedBoard>> = emptyList(),
    val pins: List<ArchiveLine<ImportedPin>> = emptyList(),
    val media: Map<String, ByteArray?> = emptyMap(),
    val readFailure: Exception? = null,
    val failAtPinLine: Int? = null,
    val unreadable: Set<String> = emptySet(),
    val entriesFailure: Exception? = null,
) : ArchiveSource {
    var closed = false
    var entryBound: Int? = null
    var metadataBound: Long? = null

    override fun entryNames(maxEntries: Int): Set<String> {
        entryBound = maxEntries
        entriesFailure?.let { throw it }
        return setOf(MANIFEST, TAGS, BOARDS, PINS) + media.keys
    }

    /** Keyed on the entry, so asking for the wrong one is a failure rather than the manifest again. */
    override fun <T : Any> readJson(name: String, type: Class<T>, maxBytes: Long): T? {
        metadataBound = maxBytes
        readFailure?.let { throw it }
        check(name == MANIFEST) { "no JSON entry named $name" }
        return type.cast(manifest)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> readJsonLines(name: String, type: Class<T>, block: (Sequence<ArchiveLine<T>>) -> Unit) {
        val lines: Sequence<ArchiveLine<*>> =
            when (name) {
                TAGS -> tags.asSequence()
                BOARDS -> boards.asSequence()
                PINS -> pinLines()
                else -> error("no JSONL entry named $name")
            }
        block(lines as Sequence<ArchiveLine<T>>)
    }

    /** Throwing while the sequence is pulled is what an interrupted attempt looks like to the runner. */
    private fun pinLines(): Sequence<ArchiveLine<ImportedPin>> =
        pins.asSequence().onEach { if (it.line == failAtPinLine) error("the archive read was interrupted") }

    override fun openEntry(name: String): InputStream? =
        when (name) {
            in unreadable -> UnreadableStream(name)
            else -> media[name]?.let { ByteArrayInputStream(it) }
        }

    override fun close() {
        closed = true
    }

    /** A bit-rotted entry: announced by the central directory, and raising once the bytes are pulled. */
    private class UnreadableStream(private val name: String) : InputStream() {
        override fun read(): Int = throw ArchiveEntryUnreadableException("entry $name is corrupt")
    }

    private companion object {
        const val MANIFEST = "manifest.json"
        const val TAGS = "tags.jsonl"
        const val BOARDS = "boards.jsonl"
        const val PINS = "pins.jsonl"
    }
}

/**
 * What [UserDataImportRunnerTest] and [UserDataImportPinWalkTest] share, split for `LargeClass`. Every
 * stub is opt-in: `BaseTest` fails a test that declares one it never reaches.
 */
@Suppress("AbstractClassCanBeConcreteClass") // Abstract by intent: a fixture base for the slices above.
internal abstract class UserDataImportRunnerFixtures : BaseTest() {
    protected val importRepository = mockk<UserDataImportRepositoryInterface>()
    protected val issueRepository = mockk<UserDataImportIssueRepositoryInterface>()
    protected val userRepository = mockk<UserRepositoryInterface>()
    protected val tagRepository = mockk<TagRepositoryInterface>()
    protected val boardRepository = mockk<BoardRepositoryInterface>()
    protected val pinRepository = mockk<PinRepositoryInterface>()
    protected val imageRepository = mockk<ImageRepositoryInterface>()
    protected val archiveStore = mockk<ImportArchiveStore>()
    protected val imageStore = mockk<ImageStore>()
    protected val imageProbe = mockk<ImageProbe>()
    protected val clock = mockk<Clock>()

    protected val accountCreatedAt: Instant = Instant.parse("2026-01-01T00:00:00Z")
    protected val now: Instant = Instant.parse("2026-08-14T10:00:00Z")
    protected val pastInstant: Instant = Instant.parse("2026-03-01T00:00:00Z")
    protected val futureInstant: Instant = Instant.parse("2027-01-01T00:00:00Z")
    protected val beforeAccount: Instant = Instant.parse("2019-05-05T00:00:00Z")

    protected val user = User(id = randomUUID(), name = "alice", createdAt = accountCreatedAt)
    protected val importId: UUID = randomUUID()
    protected val storageKey: String = ImportArchiveKey.forImport(importId)

    protected val alphaBytes: ByteArray = "alpha medium".toByteArray()
    protected val betaBytes: ByteArray = "beta medium".toByteArray()
    protected val gammaBytes: ByteArray = "gamma medium".toByteArray()
    protected val everyMedium: Map<String, ByteArray?> =
        mapOf(ALPHA_PATH to alphaBytes, BETA_PATH to betaBytes, GAMMA_PATH to gammaBytes)

    protected val runner =
        UserDataImportRunner(
            importRepository = importRepository,
            issueRepository = issueRepository,
            userRepository = userRepository,
            tagRepository = tagRepository,
            boardRepository = boardRepository,
            pinRepository = pinRepository,
            imageRepository = imageRepository,
            archiveStore = archiveStore,
            imageStore = imageStore,
            imageProbe = imageProbe,
            transactionRunner = PassthroughTransactionRunner(),
            clock = clock,
            maxMetadataBytes = MAX_METADATA_BYTES,
            maxEntries = MAX_ENTRIES,
            maxImageBytes = MAX_IMAGE_BYTES,
            maxPixels = MAX_PIXELS,
            leaseRenewalLines = LEASE_RENEWAL_LINES,
            reportDetailLimit = REPORT_DETAIL_LIMIT,
        )

    protected val rows = mutableMapOf<UUID, UserDataImport>()
    protected var stored = anImport(UserDataImportState.PENDING)
    protected var renewals = 0
    protected val renewLease: () -> Unit = { renewals++ }
    protected val savedTags = mutableListOf<Tag>()
    protected val savedBoards = mutableListOf<Board>()
    protected val savedIssues = mutableListOf<UserDataImportIssue>()
    protected val savedPins = mutableListOf<Pin>()
    protected val savedImages = mutableListOf<Image>()
    protected val existingTags = mutableMapOf<String, Tag>()
    protected val existingBoards = mutableMapOf<String, Board>()
    protected val promoted = mutableSetOf<String>()
    protected val deletedArchives = mutableListOf<String>()
    protected val stagedPaths = mutableSetOf<String>()
    protected val discarded = mutableListOf<String>()
    protected var stageCalls = 0

    /** How a re-read of the row answers, null included. The fence cases replace it instead of restubbing. */
    protected var reread: (UserDataImport) -> UserDataImport? = { it }

    /**
     * The canceller landing at the next fenced re-read, which is how a `DELETE` reaches a running walk:
     * the state is written, the token is left alone, and the runner is told by the row, not by a call.
     */
    protected fun cancelWhen(landed: () -> Boolean) {
        reread = { row ->
            when {
                landed() -> row.copy(state = UserDataImportState.CANCELLED).also { seedRow(it) }
                else -> row
            }
        }
    }

    /** What the archive store hands back. Replaced between two runs by the resumption case. */
    protected var archive = FakeArchiveSource(manifest = null)

    protected fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    protected fun anImport(
        state: UserDataImportState,
        id: UUID = importId,
        storageKey: String? = ImportArchiveKey.forImport(id),
        startedAt: Instant? = null,
    ) = UserDataImport(
        id = id,
        userId = user.id,
        state = state,
        requestedAt = now,
        storageKey = storageKey,
        startedAt = startedAt,
    )

    protected fun aManifest(pins: Int? = ANNOUNCED_PINS, formatVersion: Int = 1) =
        ImportedManifest(formatVersion = formatVersion, counts = pins?.let { ImportedCounts(pins = it) })

    protected fun aBoard(
        name: String,
        description: String = "",
        createdAt: Instant = pastInstant,
        updatedAt: Instant = pastInstant,
        deletedAt: Instant? = null,
    ) = ImportedBoard(name, description, createdAt, updatedAt, deletedAt)

    protected fun anExistingBoard(name: String, softDeletedAt: Instant? = null) =
        Board(
            id = randomUUID(),
            author = user,
            name = name,
            description = "kept",
            createdAt = accountCreatedAt,
            updatedAt = accountCreatedAt,
            softDeletedAt = softDeletedAt,
        ).also { existingBoards[name] = it }

    /** One `pins.jsonl` line over [path]; the rest is set through `copy`, so the parameter bound holds. */
    protected fun aPin(
        path: String? = ALPHA_PATH,
        bytes: ByteArray = alphaBytes,
        tags: List<String> = emptyList(),
        boards: List<String> = emptyList(),
    ) = ImportedPin(
        description = "a pin",
        sourceContextUrl = "https://example.test/alpha",
        sourceMediaUrl = null,
        createdAt = pastInstant,
        updatedAt = pastInstant,
        deletedAt = null,
        tags = tags.map { ImportedRef(it) },
        boards = boards.map { ImportedRef(it) },
        image = path?.let { ImportedImage(path = it, sha256 = sha256(bytes)) },
    )

    /** An image row the account already holds, which is what the content-hash lookup answers from. */
    protected fun anExistingImageRow(contentHash: String) =
        Image(
            id = randomUUID(),
            pinId = randomUUID(),
            mimeType = ImageFormat.PNG.mimeType,
            width = WIDTH,
            height = HEIGHT,
            animated = false,
            byteSize = 1,
            contentHash = contentHash,
            storageKey = "originals/existing",
            createdAt = accountCreatedAt,
        ).also { savedImages += it }

    protected fun seedRow(row: UserDataImport) {
        rows[row.id] = row
        stored = row
    }

    protected fun stubRow(row: UserDataImport) {
        seedRow(row)
        every { importRepository.findById(any()) } answers { rows[firstArg<UUID>()]?.let(reread) }
    }

    protected fun stubRowWrites() {
        every { importRepository.save(any()) } answers
            { firstArg<UserDataImport>().also { row -> rows[row.id] = row; stored = row } }
    }

    protected fun stubIssues() {
        every { issueRepository.save(any()) } answers
            { firstArg<UserDataImportIssue>().also { issue -> savedIssues += issue } }
    }

    protected fun stubTagLookup() {
        every { tagRepository.findUserTagByName(user, any()) } answers { existingTags[secondArg<String>()] }
    }

    /** A created row is one the lookup answers with from then on, as a repository would. */
    protected fun stubTagCreation() {
        every { tagRepository.saveTag(any()) } answers
            { firstArg<Tag>().also { tag -> savedTags += tag; existingTags[tag.name] = tag } }
    }

    protected fun stubBoardLookup() {
        every { boardRepository.findBoardForUserByName(user, any()) } answers { existingBoards[secondArg<String>()] }
    }

    protected fun stubBoardCreation() {
        every { boardRepository.saveBoard(any()) } answers
            { firstArg<Board>().also { board -> savedBoards += board; existingBoards[board.name] = board } }
    }

    /** Everything a run needs to reach the archive: the row, its writes, the account and the clock. */
    protected fun stubRunUpToOpen(row: UserDataImport = anImport(UserDataImportState.PENDING)) {
        stubRow(row)
        stubRowWrites()
        every { userRepository.findUserById(user.id) } returns user
        every { clock.now() } returns now
    }

    /** A run that reaches the archive, for the cases that end before a walk starts. */
    protected fun stubOpen(source: FakeArchiveSource, row: UserDataImport = anImport(UserDataImportState.PENDING)) {
        archive = source
        stubRunUpToOpen(row)
        every { archiveStore.open(any()) } answers { archive }
    }

    /**
     * A run that reaches the walks, where the report cap asks how many rows the report already holds and
     * whose row ends terminal, so the runner releases the archive as it returns (spec section 8 step 8).
     */
    protected fun stubWalk(
        source: FakeArchiveSource,
        row: UserDataImport = anImport(UserDataImportState.PENDING),
        storedIssues: Int = 0,
    ) {
        stubOpen(source, row)
        every { issueRepository.countForImport(any()) } returns storedIssues
        stubArchiveRelease()
    }

    /** Step 2: the entry is hashed where it lies, with nothing written. */
    protected fun stubDigest() {
        every { imageStore.digest(any(), MAX_IMAGE_BYTES) } answers { sha256(firstArg<InputStream>().readBytes()) }
    }

    /** Step 3: who already holds those bytes, answered from the image rows the account has. */
    protected fun stubHashLookup() {
        every { pinRepository.findPinIdsByContentHashForUser(user, any()) } answers {
            val contentHash = secondArg<String>()
            savedImages.filter { image -> image.contentHash == contentHash }.map { image -> image.pinId }
        }
    }

    /** Step 4: the entry is reopened and written to a temp file, which the probe then reads. */
    protected fun stubStage() {
        every { imageStore.stage(any(), MAX_IMAGE_BYTES) } answers {
            stageCalls++
            val bytes = firstArg<InputStream>().readBytes()
            StagedFile(path = "tmp/${randomUUID()}", byteSize = bytes.size.toLong(), contentHash = sha256(bytes))
                .also { file -> stagedPaths += file.path }
        }
    }

    /** Step 5's first half: the temp file is moved into place before any row is written. */
    protected fun stubPromote() {
        every { imageStore.promote(any(), any()) } answers {
            stagedPaths -= firstArg<StagedFile>().path
            promoted += secondArg<String>()
        }
    }

    protected fun stubProbe() {
        every { imageProbe.probe(any(), MAX_PIXELS) } returns
            ProbeResult(format = ImageFormat.PNG, width = WIDTH, height = HEIGHT, animated = false)
    }

    protected fun stubPinWrites() {
        every { pinRepository.savePin(any()) } answers { firstArg<Pin>().also { pin -> savedPins += pin } }
        every { imageRepository.save(any()) } answers { firstArg<Image>().also { image -> savedImages += image } }
    }

    /** The whole media path, for the cases that are about the walk rather than about one refusal. */
    protected fun stubMediaPath() {
        stubDigest()
        stubHashLookup()
        stubStage()
        stubPromote()
        stubProbe()
        stubPinWrites()
    }

    /** Step 8, and the key is asserted rather than counted: the runner deletes what it opened. */
    protected fun stubArchiveRelease() {
        every { archiveStore.delete(any()) } answers { deletedArchives += firstArg<String>() }
    }

    /**
     * Recorded rather than counted through [stagedPaths]: the promote fixture already emptied that set,
     * so asserting it holds nothing says nothing about a compensation that never ran.
     */
    protected fun stubDiscard() {
        every { imageStore.discard(any()) } answers {
            val staged = firstArg<StagedFile>()
            discarded += staged.path
            stagedPaths -= staged.path
        }
    }

    protected fun stubDelete() {
        every { imageStore.delete(any()) } answers { promoted -= firstArg<String>() }
    }

    protected fun savedTag(name: String) = savedTags.single { it.name == name }

    protected fun savedBoard(name: String) = savedBoards.single { it.name == name }

    protected fun kinds() = savedIssues.map { it.kind }

    protected fun assertCreatedNothing() {
        verify(exactly = 0) { tagRepository.saveTag(any()) }
        verify(exactly = 0) { boardRepository.saveBoard(any()) }
    }

    /** What the account holds once the walk is done, in a shape two runs can be compared on. */
    protected fun projection(): List<String> =
        savedPins
            .map { pin ->
                listOf(
                    pin.sourceContextUrl,
                    pin.description,
                    pin.createdAt,
                    pin.updatedAt,
                    pin.softDeletedAt,
                    pin.tags.map { it.name },
                    pin.boards.map { it.name },
                    savedImages.single { it.pinId == pin.id }.contentHash,
                ).joinToString("|")
            }.sorted()

    companion object {
        const val MAX_METADATA_BYTES = 16L * 1024 * 1024
        const val MAX_ENTRIES = 200_000
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
        const val MAX_PIXELS = 50_000_000L
        const val LEASE_RENEWAL_LINES = 2
        const val REPORT_DETAIL_LIMIT = 500
        const val ANNOUNCED_PINS = 7
        const val ISSUE_TEXT_LIMIT = 200
        const val OVER_LONG_NAME = 300
        const val OVER_LONG_DESCRIPTION = 2001
        const val OVER_LONG_DETAIL = 300
        const val OVER_LONG_REFS = 101
        const val HOUR_SECONDS = 3600L
        const val HASH_LENGTH = 64
        const val WIDTH = 800
        const val HEIGHT = 600
        const val ALPHA_PATH = "images/alpha.png"
        const val BETA_PATH = "images/beta.png"
        const val GAMMA_PATH = "images/gamma.png"
    }
}
