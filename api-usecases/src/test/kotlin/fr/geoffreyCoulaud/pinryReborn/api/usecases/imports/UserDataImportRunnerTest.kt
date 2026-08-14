package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportIssueKind
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveBoundExceededException
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.exceptions.PermanentTaskException
import io.mockk.every
import io.mockk.verify
import java.util.UUID.randomUUID
import java.util.zip.ZipException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The claim, the archive's refusals and the tag and board walks (spec section 8, steps 1 to 5). The pin
 * walk is [UserDataImportPinWalkTest], split off to keep both under detekt's `LargeClass` threshold.
 */
internal class UserDataImportRunnerTest : UserDataImportRunnerFixtures() {
    @Test
    fun `Given an import row that is gone, Then the runner touches nothing`() {
        // Given: an account deletion removes the row while its task is still queued
        every { importRepository.findById(importId) } returns null

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        verify(exactly = 0) { importRepository.save(any()) }
        verify(exactly = 0) { archiveStore.open(any()) }
    }

    @Test
    fun `Given a terminal import, Then the runner touches nothing`() {
        // Given: the sweep or a cancellation already settled this row
        stubRow(anImport(UserDataImportState.COMPLETED))

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

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
        assertThrows(PermanentTaskException::class.java) { runner.run(importId, isLastAttempt = false, renewLease) }
        assertEquals(UserDataImportState.FAILED, stored.state)
        assertEquals("USER_GONE", stored.failureCode)
        verify(exactly = 0) { archiveStore.open(any()) }
    }

    @Test
    fun `Given a claimed import with no storage key, Then it fails as unreadable after the claim`() {
        // Given: the projection is built after the claim, so the token it carries is never a null column
        stubRunUpToOpen(anImport(UserDataImportState.PENDING, storageKey = null))

        // When / Then
        assertThrows(PermanentTaskException::class.java) { runner.run(importId, isLastAttempt = false, renewLease) }
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
        assertThrows(PermanentTaskException::class.java) { runner.run(importId, isLastAttempt = false, renewLease) }
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
        assertThrows(PermanentTaskException::class.java) { runner.run(importId, isLastAttempt = false, renewLease) }
        assertEquals("ARCHIVE_UNREADABLE", stored.failureCode)
        assertCreatedNothing()
    }

    @Test
    fun `Given an archive past the entry bound, Then it is refused before anything is created`() {
        // Given: the entry names are read where the central directory already is, as the archive opens,
        // so an over-large one is refused before the walks create hundreds of tags and boards, and before
        // a manifest write the refusal would then revert to the claim-time row.
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                tags = listOf(TestLine(1, ImportedTag(name = "voyage", createdAt = pastInstant))),
                boards = listOf(TestLine(1, aBoard("Summer"))),
                entriesFailure = ArchiveBoundExceededException("200001 entries, past the 200000 allowed"),
            )
        stubRunUpToOpen()
        every { archiveStore.open(storageKey) } returns source

        // When / Then
        assertThrows(PermanentTaskException::class.java) { runner.run(importId, isLastAttempt = false, renewLease) }
        assertEquals(UserDataImportState.FAILED, stored.state)
        assertEquals("ARCHIVE_UNREADABLE", stored.failureCode)
        assertNull(stored.formatVersion)
        assertCreatedNothing()
    }

    @Test
    fun `Given an archive with no manifest, Then it fails as missing and the source is closed`() {
        // Given
        val source = FakeArchiveSource(manifest = null)
        stubRunUpToOpen()
        every { archiveStore.open(storageKey) } returns source

        // When / Then
        assertThrows(PermanentTaskException::class.java) { runner.run(importId, isLastAttempt = false, renewLease) }
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
        assertThrows(PermanentTaskException::class.java) { runner.run(importId, isLastAttempt = false, renewLease) }
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
        stubWalk(source)
        stubTagLookup()
        stubTagCreation()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then: the archive's own instant survives, which a runner stamping clock.now() would not leave
        assertEquals(pastInstant, savedTag("past").createdAt)
        assertEquals(now, savedTag("future").createdAt)
        assertEquals(now, stored.startedAt)
        assertEquals(1, stored.formatVersion)
        assertEquals(ANNOUNCED_PINS, stored.announcedPins)
        assertEquals(2, stored.createdTags)
        assertEquals(0, stored.skippedTags)
        // The metadata bound has no other caller in this codebase, so this is where it is pinned
        assertEquals(MAX_METADATA_BYTES, source.metadataBound)
        // Every second line, so line 1 renews nothing and line 2 renews once
        assertEquals(1, renewals)
    }

    @Test
    fun `Given an archive naming a tag and a board twice, Then the second line finds what the first created`() {
        // Given: inside one archive the walk is its own history, which a lookup blind to it would miss
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                tags =
                    listOf(
                        TestLine(1, ImportedTag(name = "voyage", createdAt = pastInstant)),
                        TestLine(2, ImportedTag(name = "voyage", createdAt = futureInstant)),
                    ),
                boards =
                    listOf(TestLine(1, aBoard("Summer")), TestLine(2, aBoard("Summer", description = "second"))),
            )
        stubWalk(source)
        stubTagLookup()
        stubTagCreation()
        stubBoardLookup()
        stubBoardCreation()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then: the first line is what survives, since a row that already exists is never modified
        assertEquals(1, stored.createdTags)
        assertEquals(1, stored.skippedTags)
        assertEquals(pastInstant, savedTag("voyage").createdAt)
        assertEquals(1, stored.createdBoards)
        assertEquals(1, stored.skippedBoards)
        assertEquals("", savedBoard("Summer").description)
        assertTrue(savedIssues.isEmpty())
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
        stubWalk(source)
        stubBoardLookup()
        stubBoardCreation()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

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
        stubWalk(source)
        stubBoardLookup()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

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
        stubWalk(source)
        stubBoardLookup()
        stubIssues()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

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
        stubWalk(source)
        stubIssues()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertCreatedNothing()
        assertEquals(List(3) { UserDataImportIssueKind.FIELD_INVALID }, kinds())
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
        stubWalk(source)
        stubTagLookup()
        stubTagCreation()
        stubBoardLookup()
        stubBoardCreation()
        stubIssues()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(List(2) { UserDataImportIssueKind.LINE_MALFORMED }, kinds())
        assertTrue(savedIssues.all { it.subject == null && it.detail == "unexpected end of input" })
        assertEquals(1, stored.createdTags)
        assertEquals(1, stored.createdBoards)
        assertEquals(2, stored.issueCount)
    }

    @Test
    fun `Given a cancellation landing before the manifest is recorded, Then no walk runs`() {
        // Given: the tag and board stubs are deliberately absent, so a walk that runs anyway fails here
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                tags = listOf(TestLine(1, ImportedTag(name = "voyage", createdAt = pastInstant))),
                boards = listOf(TestLine(1, aBoard("Summer"))),
            )
        // The report cap is never consulted either: the recorder is built from the manifest write
        stubOpen(source)
        cancelWhen { stored.state == UserDataImportState.RUNNING }

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then: the row the user was answered on stands, and the manifest write did not restore the run
        assertEquals(UserDataImportState.CANCELLED, stored.state)
        assertNull(stored.formatVersion)
        assertNull(stored.announcedPins)
    }

    @Test
    fun `Given a cancellation landing during the tag walk, Then the tally is dropped and the boards are left`() {
        // Given: the board stubs are absent, so a board walk that runs after the cancellation fails here
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                tags = listOf(TestLine(1, ImportedTag(name = "voyage", createdAt = pastInstant))),
                boards = listOf(TestLine(1, aBoard("Summer"))),
            )
        stubWalk(source)
        stubTagLookup()
        stubTagCreation()
        cancelWhen { savedTags.isNotEmpty() }

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then: the tag the walk created stays, since an import is not a transaction, but the tally goes
        assertEquals(UserDataImportState.CANCELLED, stored.state)
        assertEquals(1, savedTags.size)
        assertEquals(0, stored.createdTags)
    }

    @Test
    fun `Given a cancellation landing during the board walk, Then the pin walk never starts`() {
        // Given: a pin line the walk must never reach, since the per-pin catch-all would absorb a
        // refusal from the media stubs and report it as a rejected line rather than fail the test
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                boards = listOf(TestLine(1, aBoard("Summer"))),
                pins = listOf(TestLine(1, aPin())),
                media = everyMedium,
            )
        stubWalk(source)
        stubBoardLookup()
        stubBoardCreation()
        cancelWhen { savedBoards.isNotEmpty() }

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(UserDataImportState.CANCELLED, stored.state)
        assertEquals(0, stored.createdBoards)
        assertEquals(0, stored.processedPins)
        verify(exactly = 0) { imageStore.digest(any(), any()) }
    }

    @Test
    fun `Given a board this walk recycled itself, Then a second line naming it is a plain skip`() {
        // Given: spec section 8 step 5 reports a name held by a recycled board the import did not create.
        // The issue repository is left unstubbed, so a report written here fails the test.
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                boards =
                    listOf(TestLine(1, aBoard("Winter", deletedAt = pastInstant)), TestLine(2, aBoard("Winter"))),
            )
        stubWalk(source)
        stubBoardLookup()
        stubBoardCreation()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(1, savedBoards.size)
        assertEquals(1, stored.createdBoards)
        assertEquals(1, stored.skippedBoards)
        assertEquals(0, stored.issueCount)
    }

    @Test
    fun `Given a fenced write, Then the row it reads is read inside the transaction that writes`() {
        // Given: a row that answers CANCELLED to anyone reading it outside a transaction. A runner
        // re-reading before it opens one would believe that answer and stop, which is exactly the shape
        // the fence exists to forbid: between such a read and its write, another worker writes.
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                tags = listOf(TestLine(1, ImportedTag(name = "voyage", createdAt = pastInstant))),
            )
        stubWalk(source)
        stubTagLookup()
        stubTagCreation()
        reread = { row ->
            when {
                row.state == UserDataImportState.RUNNING && !transactions.inside ->
                    row.copy(state = UserDataImportState.CANCELLED)
                else -> row
            }
        }

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then: every fenced write landed, so none of them read the row before opening its transaction
        assertEquals(UserDataImportState.COMPLETED, stored.state)
        assertEquals(1, stored.createdTags)
    }

    @Test
    fun `Given a report the previous attempt filled, Then the cap counts those rows and stores none`() {
        // Given: the cap is seeded from what the report already holds, so a resumed attempt does not start
        // it over. The issue repository's save is left unstubbed, so one row written here fails the test.
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                tags = listOf(TestLine(1, null, failure = "unexpected end of input")),
            )
        val resumed = anImport(UserDataImportState.RUNNING).copy(issueCount = REPORT_DETAIL_LIMIT)
        stubWalk(source, resumed, storedIssues = REPORT_DETAIL_LIMIT)

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then: the count still climbs, which is what tells a capped report from a lost one
        assertTrue(savedIssues.isEmpty())
        assertEquals(REPORT_DETAIL_LIMIT + 1, stored.issueCount)
        assertTrue(stored.issueDetailTruncated)
    }

    @Test
    fun `Given a report already flagged truncated, Then an attempt with no anomaly leaves the flag alone`() {
        // Given: the other half of the seed. A walk writes the flag on every row write, so an unseeded
        // recorder would answer "not truncated" and quietly unflag a report that is still short of detail.
        val resumed = anImport(UserDataImportState.RUNNING).copy(issueDetailTruncated = true)
        stubWalk(FakeArchiveSource(aManifest()), resumed)

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(UserDataImportState.COMPLETED, stored.state)
        assertTrue(stored.issueDetailTruncated)
    }

    @Test
    fun `Given an account already holding the archive's tags, Then a resumed attempt adds to the skip counter`() {
        // Given: against a fresh account the total would be the line count either way, which proves nothing
        val names = listOf("voyage", "ete")
        names.forEach { existingTags[it] = Tag(randomUUID(), user, it, accountCreatedAt) }
        val tags = names.mapIndexed { index, name -> TestLine(index + 1, ImportedTag(name, pastInstant)) }
        val pins = listOf(TestLine(1, aPin()))
        stubWalk(FakeArchiveSource(aManifest(pins = null), tags = tags, pins = pins, failAtPinLine = 1))
        stubTagLookup()

        // When: the interruption leaves the row RUNNING, which is where the retried attempt re-enters
        assertThrows(IllegalStateException::class.java) { runner.run(importId, isLastAttempt = false, renewLease) }
        archive = FakeArchiveSource(aManifest(pins = null), tags = tags)
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(names.size * 2, stored.skippedTags)
        assertEquals(0, stored.createdTags)
        assertNull(stored.announcedPins)
        // Stamped once: the second claim keeps the instant the first one wrote
        assertEquals(now, stored.startedAt)
        assertEquals(2, renewals)
    }
}
