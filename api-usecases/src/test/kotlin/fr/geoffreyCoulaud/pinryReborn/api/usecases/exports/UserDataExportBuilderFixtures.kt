package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveEntryDigest
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveSink
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.PassthroughTransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.io.InputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.UUID.randomUUID

/**
 * A fake [ArchiveSink] recording what was written, with REAL digests: a manifest test must tell a
 * correct manifest from one that lost or mixed up entries. `order` pins the entry writing order.
 */
internal class RecordingSink : ArchiveSink {
    val text = linkedMapOf<String, String>()
    val json = linkedMapOf<String, Any>()
    val jsonLines = linkedMapOf<String, List<Any>>()
    val binary = linkedMapOf<String, ByteArray>()
    val order = mutableListOf<String>()

    override fun putTextEntry(name: String, text: String) = record(name) {
        this.text[name] = text
        text.toByteArray()
    }

    override fun putJsonEntry(name: String, value: Any) = record(name) {
        json[name] = value
        value.toString().toByteArray()
    }

    override fun putJsonLinesEntry(name: String, values: Sequence<Any>) = record(name) {
        // The real sink consumes the sequence here too: this is the one and only iteration.
        val list = values.toList()
        jsonLines[name] = list
        list.toString().toByteArray()
    }

    override fun putBinaryEntry(name: String, bytes: InputStream) = record(name) {
        val content = bytes.use { it.readBytes() }
        binary[name] = content
        content
    }

    private fun record(name: String, write: () -> ByteArray) =
        write().let { bytes ->
            order += name
            ArchiveEntryDigest(name, bytes.size.toLong(), sha256Hex(bytes))
        }

    private fun sha256Hex(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}

/**
 * A fake [ExportArchiveStore] holding what the disk holds, so a criterion about the canonical key is
 * asserted as state rather than as `verify(exactly = 0)` on the call the test itself configured.
 */
internal class FakeExportArchiveStore(
    override val format: ArchiveFormat = ArchiveFormat("application/zip", "zip"),
    /** What the next staging answers with: a var, so two attempts of one build hold distinct handles. */
    var staged: StagedFile = StagedFile(path = "tmp/staged.zip", byteSize = 1L, contentHash = "staged"),
    /** The open transaction a promote ran in, so the fence and its effect are read as one number. */
    private val transactionOf: () -> Int? = { null },
) : ExportArchiveStore {
    /** The canonical keys and the bytes each holds: what a losing attempt must never overwrite. */
    val promoted = linkedMapOf<String, StagedFile>()
    val discarded = mutableListOf<StagedFile>()
    val deleted = mutableListOf<String>()
    val promotedInTransactions = mutableListOf<Int?>()
    val sink = RecordingSink()

    /** What a promote does before it lands, so a case drives the disk failure the net must cover. */
    var beforePromote: () -> Unit = {}

    /** What lands once this attempt has staged, which is where a rival's whole transaction fits. */
    var afterStage: () -> Unit = {}

    /** What a discard does before it lands, so a case drives the unlink the net must survive. */
    var beforeDiscard: () -> Unit = {}

    // Named apart from the fixture's own stageCalls, which counts the mock: a rival keyed on the
    // wrong one never arrives, and the case goes green without testing the race.
    /** How far a build got, which is what a racing actor lands on rather than a call ordinal. */
    var stagings = 0
        private set

    override fun hasFreeSpace(requiredBytes: Long): Boolean = true

    override fun stage(block: (ArchiveSink) -> Unit): StagedFile {
        stagings++
        block(sink)
        afterStage()
        return staged
    }

    override fun promote(staged: StagedFile, storageKey: String) {
        beforePromote()
        promotedInTransactions += transactionOf()
        promoted[storageKey] = staged
    }

    override fun openStream(storageKey: String, skipBytes: Long): InputStream =
        error("a build never reads an archive back")

    override fun delete(storageKey: String) {
        deleted += storageKey
        promoted.remove(storageKey)
    }

    override fun discard(staged: StagedFile) {
        beforeDiscard()
        discarded += staged
    }

    override fun discardOrphanedStagedFiles(olderThan: Instant): Int = 0

    override fun forEachStorageKeyOnDisk(block: (Sequence<String>) -> Unit) = block(promoted.keys.asSequence())
}

/**
 * What a build over [FakeExportArchiveStore] reads and writes through, mock store excluded: a case
 * whose criterion is the disk cannot answer it with a verification on a store it never drove.
 */
@Suppress("AbstractClassCanBeConcreteClass") // Abstract by intent: a fixture base, as the import suite has.
internal abstract class UserDataExportFakeStoreFixtures : BaseTest() {
    protected val exportRepository = mockk<UserDataExportRepositoryInterface>()
    protected val userRepository = mockk<UserRepositoryInterface>()
    protected val pinRepository = mockk<PinRepositoryInterface>()
    protected val imageRepository = mockk<ImageRepositoryInterface>()
    protected val boardRepository = mockk<BoardRepositoryInterface>()
    protected val tagRepository = mockk<TagRepositoryInterface>()
    protected val imageStore = mockk<ImageStore>()
    protected val clock = mockk<Clock>()

    /** Run inline, so a read inside the fence is told from the one the build took before it. */
    protected val transactions = PassthroughTransactionRunner()

    protected val pageSize = 500
    protected val minimumFreeBytes = 1024L
    protected val retention: Duration = Duration.ofDays(7)
    protected val now: Instant = Instant.parse("2026-07-22T10:00:00Z")
    protected val userId: UUID = randomUUID()
    protected val user = User(id = userId, name = "alice", createdAt = Instant.parse("2020-01-01T00:00:00Z"))
    protected val exportId: UUID = randomUUID()
    protected val storageKey = ExportArchiveKey.forExport(exportId, "zip")
    protected val stagedByteSize = 2048L
    protected val stagedHash = "staged-sha256"

    /** The handle the first staging answers with, held apart from the fake's own var, which moves. */
    protected val stagedFile = StagedFile(path = "tmp/staged.zip", byteSize = stagedByteSize, contentHash = stagedHash)

    /** The store as a fake, for the cases whose criterion is what the disk holds afterwards. */
    protected val fakeArchiveStore = FakeExportArchiveStore(
        staged = stagedFile,
        transactionOf = { transactions.current },
    )

    /** The builder over the fake store, so a case reads the disk instead of a store's calls. */
    protected val fakeStoreBuilder = builderOver(fakeArchiveStore)

    protected fun builderOver(store: ExportArchiveStore) = UserDataExportBuilder(
        exportRepository, userRepository, pinRepository, imageRepository, boardRepository, tagRepository,
        imageStore, store, transactions, clock, applicationVersion = "1.2.3", pageSize = pageSize,
        retention = retention, minimumFreeBytes = minimumFreeBytes,
    )

    /** The rows as the store holds them, so a refusal is read as the row it left rather than as a call. */
    protected val rows = mutableMapOf<UUID, UserDataExport>()

    /** How a re-read answers, null included. The racing cases replace it rather than restubbing. */
    protected var reread: (UserDataExport) -> UserDataExport? = { it }

    /** What a row write does before it lands, so a case fails one write and lets the next through. */
    protected var beforeWrite: (UserDataExport) -> Unit = {}

    /** Which transaction each read and each write ran in, `null` outside one: a fence is one number. */
    protected val readInTransactions = mutableListOf<Int?>()
    protected val writtenInTransactions = mutableListOf<Int?>()

    protected fun anExport(formatVersion: Int = 1) =
        UserDataExport(
            id = exportId, userId = userId, state = UserDataExportState.PENDING,
            formatVersion = formatVersion, requestedAt = now,
        )

    /** The row as the store holds it now, which is what a refusal is asserted on. */
    protected fun stored(id: UUID = exportId): UserDataExport? = rows[id]

    protected fun seedRow(row: UserDataExport) {
        rows[row.id] = row
    }

    protected fun stubRow(row: UserDataExport = anExport()) {
        seedRow(row)
        every { exportRepository.findById(any()) } answers {
            readInTransactions += transactions.current
            rows[firstArg<UUID>()]?.let(reread)
        }
    }

    protected fun stubRowWrites() {
        every { exportRepository.save(any()) } answers {
            writtenInTransactions += transactions.current
            firstArg<UserDataExport>().also(beforeWrite).also(::seedRow)
        }
    }

    /** The owner's DELETE committing at the next re-read, which is how a cancellation reaches a build. */
    protected fun deleteWhen(landed: () -> Boolean) {
        reread = { row ->
            when {
                landed() -> row.copy(state = UserDataExportState.DELETED).also(::seedRow)
                else -> row
            }
        }
    }

    /** The account deletion cleaner dropping the row in that same window, leaving nothing to write over. */
    protected fun eraseWhen(landed: () -> Boolean) {
        reread = { row ->
            when {
                landed() -> {
                    rows.remove(row.id)
                    null
                }
                else -> row
            }
        }
    }

    /**
     * The other attempt of the same build, whole, between this one's staging and its completion. A
     * rival landing inside the fence's own read instead is overwritten by a promote placed before it.
     */
    protected fun rivalPublishes(rivalStaged: StagedFile) {
        fakeArchiveStore.afterStage = {
            fakeArchiveStore.promote(rivalStaged, storageKey)
            val row = requireNotNull(stored()) { "the rival publishes over this attempt's own stamped row" }
            seedRow(
                row.copy(
                    state = UserDataExportState.READY, storageKey = storageKey,
                    byteSize = rivalStaged.byteSize, sha256 = rivalStaged.contentHash,
                ),
            )
        }
    }

    /** Stubs a single active-pin page holding exactly [pins]. */
    protected fun stubActivePins(pins: List<Pin>) {
        every { pinRepository.findPinsForUser(user, null, pageSize, PinSortStrategy.CREATED_AT_DESC) } returns
            Page(items = pins, previousCursor = null, nextCursor = null)
    }

    /** Stubs a single recycled-pin page holding exactly [pins]. */
    protected fun stubRecycledPins(pins: List<Pin>) {
        every {
            pinRepository.findSoftDeletedPinsForUser(user, null, pageSize, PinSortStrategy.DELETED_AT_DESC)
        } returns Page(items = pins, previousCursor = null, nextCursor = null)
    }

    /** An empty single page for both pin walks, and empty boards and tags: the "nothing but the shell" case. */
    protected fun stubEmptyCollections() {
        stubActivePins(emptyList())
        stubRecycledPins(emptyList())
        every { boardRepository.findActiveBoardsForUser(user) } returns emptyList()
        every { boardRepository.findRecycledBoardsForUser(user) } returns emptyList()
        every { tagRepository.findAllTagsForUser(user) } returns emptyList()
    }

    /**
     * The whole path for [fakeStoreBuilder]: the fake answers the free space, the format and the
     * staging itself, so no mock store is stubbed and `checkUnnecessaryStub` stays satisfied.
     */
    protected fun stubFakeStoreBuild(row: UserDataExport = anExport()) {
        stubRow(row)
        stubRowWrites()
        every { userRepository.findUserById(userId) } returns user
        every { clock.now() } returns now
        stubEmptyCollections()
    }
}

/**
 * The same fixtures plus the archive store as a mock, for the cases whose window opens before the
 * completion: the entry guards, the two fenced writes and the staging failures.
 */
@Suppress("AbstractClassCanBeConcreteClass") // Abstract by intent: a fixture base, as the import suite has.
internal abstract class UserDataExportBuilderFixtures : UserDataExportFakeStoreFixtures() {
    protected val archiveStore = mockk<ExportArchiveStore>()

    protected val builder = builderOver(archiveStore)

    protected lateinit var sink: RecordingSink

    /** How far the build got, which is what a racing actor lands on rather than a call ordinal. */
    protected var stageCalls = 0

    protected fun aPin(
        id: UUID = randomUUID(),
        tags: List<Tag> = emptyList(),
        softDeletedAt: Instant? = null,
        createdAt: Instant = now,
        updatedAt: Instant = now,
    ) = Pin(
        id = id, author = user, sourceContextUrl = "https://example.org/a", sourceMediaUrl = null,
        description = "desc", tags = tags, boards = emptyList(), softDeletedAt = softDeletedAt,
        createdAt = createdAt, updatedAt = updatedAt,
    )

    protected fun anImage(pinId: UUID, id: UUID = randomUUID(), mimeType: String = "image/jpeg") = Image(
        id = id, pinId = pinId, mimeType = mimeType, width = 10, height = 10, animated = false,
        byteSize = 3L, contentHash = "content-hash", storageKey = "originals/$id", createdAt = now,
    )

    protected fun aBoard(id: UUID = randomUUID(), name: String = "board", softDeletedAt: Instant? = null) = Board(
        id = id, author = user, name = name, description = "d", softDeletedAt = softDeletedAt,
        createdAt = now, updatedAt = now,
    )

    protected fun stubArchiveStore() {
        sink = RecordingSink()
        every { archiveStore.stage(any()) } answers {
            stageCalls++
            firstArg<(ArchiveSink) -> Unit>().invoke(sink)
            StagedFile(path = "tmp/staged.zip", byteSize = stagedByteSize, contentHash = stagedHash)
        }
    }

    /** A staging that fails once the build has committed to it, which is the window site 2 answers for. */
    protected fun stubFailingStage() {
        every { archiveStore.stage(any()) } answers {
            stageCalls++
            error("the archive could not be staged")
        }
    }

    /** What a build reads before it stages: the row, the account, the disk and the archive format. */
    protected fun stubBuildEntry(row: UserDataExport = anExport()) {
        stubRow(row)
        every { userRepository.findUserById(userId) } returns user
        every { archiveStore.hasFreeSpace(minimumFreeBytes) } returns true
        every { archiveStore.format } returns ArchiveFormat("application/zip", "zip")
    }

    /** A build that reaches its staging, whatever the staging then does. */
    protected fun stubBuildToStaging(row: UserDataExport = anExport()) {
        stubBuildEntry(row)
        stubRowWrites()
        every { clock.now() } returns now
    }

    /** The whole path: an empty archive, staged, promoted and published. */
    protected fun stubHappyPathBuild(row: UserDataExport = anExport()) {
        stubBuildToStaging(row)
        stubArchiveStore()
        stubEmptyCollections()
        every { archiveStore.promote(any(), any()) } just runs
    }
}
