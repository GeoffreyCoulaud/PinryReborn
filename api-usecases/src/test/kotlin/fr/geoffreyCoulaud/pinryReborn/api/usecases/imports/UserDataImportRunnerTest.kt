package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImportIssue
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportIssueKind
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveBoundExceededException
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveLine
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveSource
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportIssueRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.exceptions.PermanentTaskException
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.InputStream
import java.time.Instant
import java.util.UUID.randomUUID
import java.util.zip.ZipException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The ambient transaction the claim runs in; this suite owns no connection. */
private class PassthroughTransactionRunner : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T = block()
}

private data class TestLine<out T>(
    override val line: Int,
    override val value: T?,
    override val failure: String? = null,
) : ArchiveLine<T>

/**
 * An [ArchiveSource] over typed lines: a runner test says what the archive holds instead of building a
 * ZIP. Framing and mapper belong to the adapter's own suite, and the real bytes to block 10's round trip.
 */
private class FakeArchiveSource(
    private val manifest: ImportedManifest?,
    private val tags: List<ArchiveLine<ImportedTag>> = emptyList(),
    private val boards: List<ArchiveLine<ImportedBoard>> = emptyList(),
    private val readFailure: Exception? = null,
) : ArchiveSource {
    var closed = false

    override fun entryNames(maxEntries: Int): Set<String> = setOf("manifest.json", "tags.jsonl", "boards.jsonl")

    override fun <T : Any> readJson(name: String, type: Class<T>, maxBytes: Long): T? {
        readFailure?.let { throw it }
        return type.cast(manifest)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> readJsonLines(name: String, type: Class<T>, block: (Sequence<ArchiveLine<T>>) -> Unit) {
        val lines = if (name == "tags.jsonl") tags else boards
        block(lines.asSequence() as Sequence<ArchiveLine<T>>)
    }

    override fun openEntry(name: String): InputStream? = null

    override fun close() {
        closed = true
    }
}

class UserDataImportRunnerTest : BaseTest() {
    private val importRepository = mockk<UserDataImportRepositoryInterface>()
    private val issueRepository = mockk<UserDataImportIssueRepositoryInterface>()
    private val userRepository = mockk<UserRepositoryInterface>()
    private val tagRepository = mockk<TagRepositoryInterface>()
    private val boardRepository = mockk<BoardRepositoryInterface>()
    private val archiveStore = mockk<ImportArchiveStore>()
    private val clock = mockk<Clock>()

    private val accountCreatedAt = Instant.parse("2026-01-01T00:00:00Z")
    private val now = Instant.parse("2026-08-14T10:00:00Z")
    private val pastInstant = Instant.parse("2026-03-01T00:00:00Z")
    private val futureInstant = Instant.parse("2027-01-01T00:00:00Z")
    private val beforeAccount = Instant.parse("2019-05-05T00:00:00Z")

    private val user = User(id = randomUUID(), name = "alice", createdAt = accountCreatedAt)
    private val importId = randomUUID()
    private val storageKey = ImportArchiveKey.forImport(importId)

    private val runner =
        UserDataImportRunner(
            importRepository = importRepository,
            issueRepository = issueRepository,
            userRepository = userRepository,
            tagRepository = tagRepository,
            boardRepository = boardRepository,
            archiveStore = archiveStore,
            transactionRunner = PassthroughTransactionRunner(),
            clock = clock,
            maxMetadataBytes = MAX_METADATA_BYTES,
            leaseRenewalLines = LEASE_RENEWAL_LINES,
        )

    private var stored = anImport(UserDataImportState.PENDING)
    private var renewals = 0
    private val renewLease: () -> Unit = { renewals++ }
    private val savedTags = mutableListOf<Tag>()
    private val savedBoards = mutableListOf<Board>()
    private val savedIssues = mutableListOf<UserDataImportIssue>()
    private val existingTags = mutableMapOf<String, Tag>()
    private val existingBoards = mutableMapOf<String, Board>()

    private fun anImport(
        state: UserDataImportState,
        storageKey: String? = this.storageKey,
        startedAt: Instant? = null,
    ) = UserDataImport(
        id = importId,
        userId = user.id,
        state = state,
        requestedAt = now,
        storageKey = storageKey,
        startedAt = startedAt,
    )

    private fun aManifest(pins: Int? = ANNOUNCED_PINS, formatVersion: Int = 1) =
        ImportedManifest(formatVersion = formatVersion, counts = pins?.let { ImportedCounts(pins = it) })

    private fun aBoard(
        name: String,
        description: String = "",
        createdAt: Instant = pastInstant,
        updatedAt: Instant = pastInstant,
        deletedAt: Instant? = null,
    ) = ImportedBoard(name, description, createdAt, updatedAt, deletedAt)

    private fun anExistingBoard(name: String, softDeletedAt: Instant? = null) =
        Board(
            id = randomUUID(),
            author = user,
            name = name,
            description = "kept",
            createdAt = accountCreatedAt,
            updatedAt = accountCreatedAt,
            softDeletedAt = softDeletedAt,
        ).also { existingBoards[name] = it }

    private fun stubRow(row: UserDataImport) {
        stored = row
        every { importRepository.findById(importId) } answers { stored }
    }

    private fun stubRowWrites() {
        every { importRepository.save(any()) } answers { firstArg<UserDataImport>().also { row -> stored = row } }
    }

    private fun stubIssues() {
        every { issueRepository.save(any()) } answers
            { firstArg<UserDataImportIssue>().also { issue -> savedIssues += issue } }
    }

    private fun stubTagLookup() {
        every { tagRepository.findUserTagByName(user, any()) } answers { existingTags[secondArg<String>()] }
    }

    private fun stubTagCreation() {
        every { tagRepository.saveTag(any()) } answers { firstArg<Tag>().also { tag -> savedTags += tag } }
    }

    private fun stubBoardLookup() {
        every { boardRepository.findBoardForUserByName(user, any()) } answers { existingBoards[secondArg<String>()] }
    }

    private fun stubBoardCreation() {
        every { boardRepository.saveBoard(any()) } answers { firstArg<Board>().also { board -> savedBoards += board } }
    }

    /** Everything a run needs to reach the archive: the row, its writes, the account and the clock. */
    private fun stubRunUpToOpen(row: UserDataImport = anImport(UserDataImportState.PENDING)) {
        stubRow(row)
        stubRowWrites()
        every { userRepository.findUserById(user.id) } returns user
        every { clock.now() } returns now
    }

    private fun savedTag(name: String) = savedTags.single { it.name == name }

    private fun savedBoard(name: String) = savedBoards.single { it.name == name }

    private fun assertCreatedNothing() {
        verify(exactly = 0) { tagRepository.saveTag(any()) }
        verify(exactly = 0) { boardRepository.saveBoard(any()) }
    }

    @Test
    fun `Given an import row that is gone, Then the runner touches nothing`() {
        // Given: an account deletion removes the row while its task is still queued
        every { importRepository.findById(importId) } returns null

        // When
        runner.run(importId, renewLease)

        // Then
        verify(exactly = 0) { importRepository.save(any()) }
        verify(exactly = 0) { archiveStore.open(any()) }
    }

    @Test
    fun `Given a terminal import, Then the runner touches nothing`() {
        // Given: the sweep or a cancellation already settled this row
        stubRow(anImport(UserDataImportState.COMPLETED))

        // When
        runner.run(importId, renewLease)

        // Then
        verify(exactly = 0) { importRepository.save(any()) }
        verify(exactly = 0) { archiveStore.open(any()) }
    }

    @Test
    fun `Given an import whose account is gone, Then it fails with USER GONE and is not retried`() {
        // Given: findUserById hides a tombstoned account, so a deletion in flight lands here too
        stubRow(anImport(UserDataImportState.PENDING))
        stubRowWrites()
        every { userRepository.findUserById(user.id) } returns null

        // When / Then
        assertThrows(PermanentTaskException::class.java) { runner.run(importId, renewLease) }
        assertEquals(UserDataImportState.FAILED, stored.state)
        assertEquals("USER_GONE", stored.failureCode)
        verify(exactly = 0) { archiveStore.open(any()) }
    }

    @Test
    fun `Given a claimed import with no storage key, Then it fails as unreadable after the claim`() {
        // Given: the projection is built after the claim, so the token it carries is never a null column
        stubRunUpToOpen(anImport(UserDataImportState.PENDING, storageKey = null))

        // When / Then
        assertThrows(PermanentTaskException::class.java) { runner.run(importId, renewLease) }
        assertEquals(UserDataImportState.FAILED, stored.state)
        assertEquals("ARCHIVE_UNREADABLE", stored.failureCode)
        assertNotNull(stored.runToken)
        assertEquals(now, stored.startedAt)
        verify(exactly = 0) { archiveStore.open(any()) }
    }

    @Test
    fun `Given an archive that cannot be opened, Then it fails as unreadable and creates nothing`() {
        // Given
        stubRunUpToOpen()
        every { archiveStore.open(storageKey) } throws ZipException("not a zip file")

        // When / Then
        assertThrows(PermanentTaskException::class.java) { runner.run(importId, renewLease) }
        assertEquals(UserDataImportState.FAILED, stored.state)
        assertEquals("ARCHIVE_UNREADABLE", stored.failureCode)
        assertEquals(0, stored.processedPins)
        assertCreatedNothing()
    }

    @Test
    fun `Given a manifest past the metadata bound, Then the archive is refused as unreadable`() {
        // Given: a refused read is not an I/O failure, and both answers are the same permanent refusal
        val source = FakeArchiveSource(aManifest(), readFailure = ArchiveBoundExceededException("too large"))
        stubRunUpToOpen()
        every { archiveStore.open(storageKey) } returns source

        // When / Then
        assertThrows(PermanentTaskException::class.java) { runner.run(importId, renewLease) }
        assertEquals("ARCHIVE_UNREADABLE", stored.failureCode)
        assertCreatedNothing()
    }

    @Test
    fun `Given an archive with no manifest, Then it fails as missing and the source is closed`() {
        // Given
        val source = FakeArchiveSource(manifest = null)
        stubRunUpToOpen()
        every { archiveStore.open(storageKey) } returns source

        // When / Then
        assertThrows(PermanentTaskException::class.java) { runner.run(importId, renewLease) }
        assertEquals(UserDataImportState.FAILED, stored.state)
        assertEquals("MANIFEST_MISSING", stored.failureCode)
        assertEquals(0, stored.processedPins)
        assertTrue(source.closed)
        assertCreatedNothing()
    }

    @Test
    fun `Given an archive of another format version, Then it is refused without a retry`() {
        // Given: version 1 is the only contract this importer has
        val source = FakeArchiveSource(aManifest(formatVersion = 2))
        stubRunUpToOpen()
        every { archiveStore.open(storageKey) } returns source

        // When / Then
        assertThrows(PermanentTaskException::class.java) { runner.run(importId, renewLease) }
        assertEquals(UserDataImportState.FAILED, stored.state)
        assertEquals("UNSUPPORTED_FORMAT_VERSION", stored.failureCode)
        assertEquals(0, stored.processedPins)
        assertCreatedNothing()
    }

    @Test
    fun `Given past and future instants, Then each is restored or clamped and the row is stamped by the clock`() {
        // Given: the past one lies between the account's creation and the import, so the clamp is a no-op
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                tags =
                    listOf(
                        TestLine(1, ImportedTag(name = "past", createdAt = pastInstant)),
                        TestLine(2, ImportedTag(name = "future", createdAt = futureInstant)),
                    ),
            )
        stubRunUpToOpen()
        every { archiveStore.open(storageKey) } returns source
        stubTagLookup()
        stubTagCreation()

        // When
        runner.run(importId, renewLease)

        // Then: the archive's own instant survives, which a runner stamping clock.now() would not leave
        assertEquals(pastInstant, savedTag("past").createdAt)
        assertEquals(now, savedTag("future").createdAt)
        assertEquals(now, stored.startedAt)
        assertEquals(1, stored.formatVersion)
        assertEquals(ANNOUNCED_PINS, stored.announcedPins)
        assertEquals(2, stored.createdTags)
        assertEquals(0, stored.skippedTags)
        // Every second line, so line 1 renews nothing and line 2 renews once
        assertEquals(1, renewals)
    }

    @Test
    fun `Given boards the import creates, Then their instants are raised, floored and their recycled state kept`() {
        // Given
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                boards =
                    listOf(
                        TestLine(1, aBoard("old", updatedAt = pastInstant.minusSeconds(HOUR_SECONDS))),
                        TestLine(2, aBoard("ancient", createdAt = beforeAccount, updatedAt = beforeAccount)),
                        TestLine(3, aBoard("archived", deletedAt = futureInstant)),
                    ),
            )
        stubRunUpToOpen()
        every { archiveStore.open(storageKey) } returns source
        stubBoardLookup()
        stubBoardCreation()

        // When
        runner.run(importId, renewLease)

        // Then
        assertEquals(pastInstant, savedBoard("old").updatedAt)
        assertEquals(accountCreatedAt, savedBoard("ancient").createdAt)
        assertEquals(accountCreatedAt, savedBoard("ancient").updatedAt)
        assertEquals(now, savedBoard("archived").softDeletedAt)
        assertNull(savedBoard("old").softDeletedAt)
        assertEquals(3, stored.createdBoards)
        assertEquals(0, stored.skippedBoards)
    }

    @Test
    fun `Given an existing active board of that name, Then the archive's recycled copy leaves it untouched`() {
        // Given: the case that broke the first draft of the spec
        anExistingBoard("Summer")
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                boards = listOf(TestLine(1, aBoard("Summer", description = "from the archive", deletedAt = now))),
            )
        stubRunUpToOpen()
        every { archiveStore.open(storageKey) } returns source
        stubBoardLookup()

        // When
        runner.run(importId, renewLease)

        // Then: no write at all is what "left untouched" means, state, description and updatedAt included
        verify(exactly = 0) { boardRepository.saveBoard(any()) }
        assertEquals(1, stored.skippedBoards)
        assertEquals(0, stored.createdBoards)
        assertTrue(savedIssues.isEmpty())
    }

    @Test
    fun `Given a name held only by a recycled board, Then it is reported and nothing is created`() {
        // Given: a recycled board holds its name, so the archive's active board cannot take it
        anExistingBoard("Winter", softDeletedAt = pastInstant)
        val source =
            FakeArchiveSource(manifest = aManifest(), boards = listOf(TestLine(1, aBoard("Winter"))))
        stubRunUpToOpen()
        every { archiveStore.open(storageKey) } returns source
        stubBoardLookup()
        stubIssues()

        // When
        runner.run(importId, renewLease)

        // Then
        verify(exactly = 0) { boardRepository.saveBoard(any()) }
        assertEquals(UserDataImportIssueKind.NAME_TAKEN_BY_RECYCLED, savedIssues.single().kind)
        assertEquals("Winter", savedIssues.single().subject)
        assertNull(savedIssues.single().detail)
        assertEquals(1, stored.skippedBoards)
        assertEquals(1, stored.issueCount)
    }

    @Test
    fun `Given lines past the field bounds, Then each is reported invalid and skipped`() {
        // Given: spec section 4.1's bounds, restated because no entity carries them
        val longName = "n".repeat(OVER_LONG_NAME)
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                tags = listOf(TestLine(1, ImportedTag(name = "  ", createdAt = pastInstant))),
                boards =
                    listOf(
                        TestLine(1, aBoard(longName)),
                        TestLine(2, aBoard("fine", description = "d".repeat(OVER_LONG_DESCRIPTION))),
                    ),
            )
        stubRunUpToOpen()
        every { archiveStore.open(storageKey) } returns source
        stubIssues()

        // When
        runner.run(importId, renewLease)

        // Then
        assertCreatedNothing()
        assertEquals(List(3) { UserDataImportIssueKind.FIELD_INVALID }, savedIssues.map { it.kind })
        assertEquals(3, stored.issueCount)
        // Stored at the report's own bound, so a hostile line cannot make the report the payload
        assertEquals(longName.take(ISSUE_TEXT_LIMIT), savedIssues[1].subject)
        assertTrue(savedIssues[2].detail?.contains("longer than") == true)
    }

    @Test
    fun `Given a malformed line, Then it is reported and the walk continues`() {
        // Given: one bad entry never fails an import
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                tags =
                    listOf(
                        TestLine(1, null, failure = "unexpected end of input"),
                        TestLine(2, ImportedTag(name = "kept", createdAt = pastInstant)),
                    ),
                boards = listOf(TestLine(1, null, failure = "unexpected end of input"), TestLine(2, aBoard("kept"))),
            )
        stubRunUpToOpen()
        every { archiveStore.open(storageKey) } returns source
        stubTagLookup()
        stubTagCreation()
        stubBoardLookup()
        stubBoardCreation()
        stubIssues()

        // When
        runner.run(importId, renewLease)

        // Then
        assertEquals(List(2) { UserDataImportIssueKind.LINE_MALFORMED }, savedIssues.map { it.kind })
        assertTrue(savedIssues.all { it.subject == null && it.detail == "unexpected end of input" })
        assertEquals(1, stored.createdTags)
        assertEquals(1, stored.createdBoards)
        assertEquals(2, stored.issueCount)
    }

    @Test
    fun `Given an account already holding the archive's tags, Then a second walk doubles the skip counter`() {
        // Given: against a fresh account the total would be the line count either way, which proves nothing
        val names = listOf("voyage", "ete")
        names.forEach { existingTags[it] = Tag(randomUUID(), user, it, accountCreatedAt) }
        val source =
            FakeArchiveSource(
                manifest = aManifest(pins = null),
                tags = names.mapIndexed { index, name -> TestLine(index + 1, ImportedTag(name, pastInstant)) },
            )
        stubRunUpToOpen()
        every { archiveStore.open(storageKey) } returns source
        stubTagLookup()

        // When: the row is left RUNNING by the first walk, which is where a retried attempt re-enters
        runner.run(importId, renewLease)
        runner.run(importId, renewLease)

        // Then
        assertEquals(names.size * 2, stored.skippedTags)
        assertEquals(0, stored.createdTags)
        assertNull(stored.announcedPins)
        // Stamped once: the second claim keeps the instant the first one wrote
        assertEquals(now, stored.startedAt)
        assertEquals(2, renewals)
    }

    private companion object {
        const val MAX_METADATA_BYTES = 16L * 1024 * 1024
        const val LEASE_RENEWAL_LINES = 2
        const val ANNOUNCED_PINS = 7
        const val ISSUE_TEXT_LIMIT = 200
        const val OVER_LONG_NAME = 300
        const val OVER_LONG_DESCRIPTION = 2001
        const val HOUR_SECONDS = 3600L
    }
}
