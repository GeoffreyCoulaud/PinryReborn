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
 * What the builder suite reads and writes through, split off for the 600-line class bound: a row store
 * the writes land in, and a re-read hook a racing actor lands on (fencing spec section 9).
 */
@Suppress("AbstractClassCanBeConcreteClass") // Abstract by intent: a fixture base, as the import suite has.
internal abstract class UserDataExportBuilderFixtures : BaseTest() {
    protected val exportRepository = mockk<UserDataExportRepositoryInterface>()
    protected val userRepository = mockk<UserRepositoryInterface>()
    protected val pinRepository = mockk<PinRepositoryInterface>()
    protected val imageRepository = mockk<ImageRepositoryInterface>()
    protected val boardRepository = mockk<BoardRepositoryInterface>()
    protected val tagRepository = mockk<TagRepositoryInterface>()
    protected val imageStore = mockk<ImageStore>()
    protected val archiveStore = mockk<ExportArchiveStore>()
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
    protected val storageKey = "exports/$exportId.zip"
    protected val stagedByteSize = 2048L
    protected val stagedHash = "staged-sha256"

    protected val builder = UserDataExportBuilder(
        exportRepository, userRepository, pinRepository, imageRepository, boardRepository, tagRepository,
        imageStore, archiveStore, transactions, clock, applicationVersion = "1.2.3", pageSize = pageSize,
        retention = retention, minimumFreeBytes = minimumFreeBytes,
    )

    protected lateinit var sink: RecordingSink

    /** The rows as the store holds them, so a refusal is read as the row it left rather than as a call. */
    protected val rows = mutableMapOf<UUID, UserDataExport>()

    /** How a re-read answers, null included. The racing cases replace it rather than restubbing. */
    protected var reread: (UserDataExport) -> UserDataExport? = { it }

    /** How far the build got, which is what a racing actor lands on rather than a call ordinal. */
    protected var stageCalls = 0

    /** Which transaction each read and each write ran in, `null` outside one: a fence is one number. */
    protected val readInTransactions = mutableListOf<Int?>()
    protected val writtenInTransactions = mutableListOf<Int?>()

    protected fun anExport(formatVersion: Int = 1) =
        UserDataExport(
            id = exportId, userId = userId, state = UserDataExportState.PENDING,
            formatVersion = formatVersion, requestedAt = now,
        )

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
            firstArg<UserDataExport>().also(::seedRow)
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
