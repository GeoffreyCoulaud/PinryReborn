package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.ImageFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportIssueKind
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooLargeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooManyPixelsException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UndecodableImageException
import io.mockk.every
import io.mockk.verify
import java.io.IOException
import java.util.UUID.randomUUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pin walk (spec section 8, step 6 and the per-pin sequence): the fence, the cursor, every issue
 * kind a pin line produces, and the report cap. Split from [UserDataImportRunnerTest] for `LargeClass`.
 */
internal class UserDataImportPinWalkTest : UserDataImportRunnerFixtures() {
    private val secondImportId = randomUUID()

    @Test
    fun `Given a pin whose medium is new, Then it is created with its clamped instants and its memberships`() {
        // Given: "ghost" resolves to nothing, since a name the metadata walk refused is never created here
        existingTags["holidays"] = Tag(randomUUID(), user, "holidays", accountCreatedAt)
        anExistingBoard("Summer")
        val line = aPin(tags = listOf("holidays", "ghost"), boards = listOf("Summer", "ghost"))
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                pins =
                    listOf(
                        TestLine(1, line.copy(updatedAt = futureInstant)),
                        TestLine(2, aPin(path = BETA_PATH, bytes = betaBytes).copy(deletedAt = pastInstant)),
                    ),
                media = everyMedium,
            )
        stubWalk(source)
        stubTagLookup()
        stubBoardLookup()
        stubMediaPath()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then: the medium's own bytes decide the stored type and hash, never the manifest
        val created = savedPins.first()
        assertEquals(pastInstant, created.createdAt)
        assertEquals(now, created.updatedAt)
        assertEquals(listOf("holidays"), created.tags.map { it.name })
        assertEquals(listOf("Summer"), created.boards.map { it.name })
        assertEquals(pastInstant, savedPins[1].softDeletedAt)
        assertNull(created.softDeletedAt)
        assertEquals(sha256(alphaBytes), savedImages.first().contentHash)
        assertEquals(ImageFormat.PNG.mimeType, savedImages.first().mimeType)
        assertEquals(now, savedImages.first().createdAt)
        assertEquals(savedImages.map { it.storageKey }.toSet(), promoted)
        assertTrue(stagedPaths.isEmpty())
        assertEquals(2, stored.processedPins)
        assertEquals(2, stored.createdPins)
        assertEquals(0, stored.skippedPins)
        // The entry-count bound has no other caller in this codebase, so this is where it is pinned
        assertEquals(MAX_ENTRIES, source.entryBound)
        // One renewal per pin, as spec section 8 step 6 requires
        assertEquals(2, renewals)
    }

    @Test
    fun `Given an archive imported twice, Then the second run stages nothing and creates no pin`() {
        // Given: two imports of one archive, since re-running one row resumes from its own cursor instead.
        // Counting stage rather than the probe is deliberate and has no other channel: an implementation
        // that stages first and discards on a hit probes zero times too, having written every byte.
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                pins = listOf(TestLine(1, aPin()), TestLine(2, aPin(path = BETA_PATH, bytes = betaBytes))),
                media = everyMedium,
            )
        stubWalk(source)
        stubMediaPath()
        runner.run(importId, isLastAttempt = false, renewLease)
        val first = projection()
        val stagedOnce = stageCalls

        // When
        seedRow(anImport(UserDataImportState.PENDING, id = secondImportId))
        runner.run(secondImportId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(2, stagedOnce)
        assertEquals(stagedOnce, stageCalls)
        assertEquals(first, projection())
        assertEquals(0, stored.createdPins)
        assertEquals(2, stored.skippedPins)
        assertEquals(2, stored.processedPins)
        assertTrue(savedIssues.isEmpty())
    }

    @Test
    fun `Given a run token that changed mid-walk, Then the walk stops and leaves no bytes behind`() {
        // Given: a lease expiry hands the row to a second worker, which no real worker can be made to do
        // deterministically. The fake stands for it, and that limit is stated rather than slept through.
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                pins =
                    listOf(
                        TestLine(1, aPin()),
                        TestLine(2, aPin(path = BETA_PATH, bytes = betaBytes)),
                        TestLine(3, aPin(path = GAMMA_PATH, bytes = gammaBytes)),
                    ),
                media = everyMedium,
            )
        // No release stub: the row is still RUNNING, under the token of the runner now reading these bytes
        stubOpen(source)
        every { issueRepository.countForImport(any()) } returns 0
        stubMediaPath()
        stubDiscard()
        stubDelete()
        reread = { row -> if (savedPins.size < 2) row else row.copy(runToken = randomUUID()) }

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then: the third pin's bytes were promoted before the fence answered, and both halves go
        assertEquals(2, savedPins.size)
        assertEquals(2, stored.processedPins)
        assertEquals(2, stored.createdPins)
        assertEquals(savedImages.map { it.storageKey }.toSet(), promoted)
        assertTrue(stagedPaths.isEmpty())
        verify(exactly = 0) { archiveStore.delete(any()) }
    }

    @Test
    fun `Given a cancellation written mid-walk, Then the walk stops on the state the canceller left`() {
        // Given: the canceller keeps the token and only writes CANCELLED, so the fence reads both
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                pins = listOf(TestLine(1, aPin()), TestLine(2, aPin(path = BETA_PATH, bytes = betaBytes))),
                media = everyMedium,
            )
        stubWalk(source)
        stubMediaPath()
        stubDiscard()
        stubDelete()
        reread = { row ->
            when {
                savedPins.isEmpty() -> row
                else -> row.copy(state = UserDataImportState.CANCELLED).also { seedRow(it) }
            }
        }

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(1, savedPins.size)
        assertEquals(UserDataImportState.CANCELLED, stored.state)
        assertEquals(savedImages.map { it.storageKey }.toSet(), promoted)
        assertTrue(stagedPaths.isEmpty())
    }

    @Test
    fun `Given the import row deleted mid-walk, Then the walk stops and leaves no bytes behind`() {
        // Given: an account deletion lands while the walk holds the archive. Writing on regardless would
        // re-insert a row for an account that is gone, which foreign keys do not stop on this datasource.
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                pins = listOf(TestLine(1, aPin()), TestLine(2, aPin(path = BETA_PATH, bytes = betaBytes))),
                media = everyMedium,
            )
        stubWalk(source)
        stubMediaPath()
        stubDiscard()
        stubDelete()
        reread = { row -> if (savedPins.isEmpty()) row else null }

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(1, savedPins.size)
        assertEquals(savedImages.map { it.storageKey }.toSet(), promoted)
        assertTrue(stagedPaths.isEmpty())
    }

    @Test
    fun `Given a per-pin transaction that throws, Then the promoted bytes go and the line is reported`() {
        // Given: the resumption case throws before anything is staged, so this path needs its own case
        val source =
            FakeArchiveSource(manifest = aManifest(), pins = listOf(TestLine(1, aPin())), media = everyMedium)
        stubWalk(source)
        stubDigest()
        stubHashLookup()
        stubStage()
        stubPromote()
        stubProbe()
        stubDiscard()
        stubDelete()
        stubIssues()
        every { pinRepository.savePin(any()) } answers { firstArg<Pin>().also { pin -> savedPins += pin } }
        every { imageRepository.save(any()) } throws IllegalStateException("constraint violation")

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then: the pin row's own fate is the real transaction's, which this passthrough cannot roll back
        assertTrue(promoted.isEmpty())
        assertTrue(stagedPaths.isEmpty())
        assertEquals(listOf(UserDataImportIssueKind.LINE_REJECTED), kinds())
        assertEquals(1, stored.processedPins)
        assertEquals(0, stored.createdPins)
        assertEquals(1, stored.skippedPins)
    }

    @Test
    fun `Given an interrupted walk, Then a second run resumes from the cursor and duplicates nothing`() {
        // Given
        val pins =
            listOf(
                TestLine(1, aPin()),
                TestLine(2, aPin(path = BETA_PATH, bytes = betaBytes)),
                TestLine(3, aPin(path = GAMMA_PATH, bytes = gammaBytes)),
            )
        stubWalk(FakeArchiveSource(aManifest(), pins = pins, media = everyMedium, failAtPinLine = 3))
        stubMediaPath()

        // When
        assertThrows(IllegalStateException::class.java) { runner.run(importId, isLastAttempt = false, renewLease) }
        archive = FakeArchiveSource(aManifest(), pins = pins, media = everyMedium)
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then: three pins, not five, and the counters are the sums of both attempts
        assertEquals(3, savedPins.size)
        assertEquals(3, savedImages.size)
        assertEquals(3, stageCalls)
        assertEquals(3, stored.processedPins)
        assertEquals(3, stored.createdPins)
        assertEquals(0, stored.skippedPins)
        assertEquals(savedImages.map { it.storageKey }.toSet(), promoted)
        assertTrue(savedIssues.isEmpty())
    }

    @Test
    fun `Given a pin with no medium, Then it is reported and skipped`() {
        // Given: a pin is metadata over a medium, so nothing anchors this one
        val source = FakeArchiveSource(aManifest(), pins = listOf(TestLine(1, aPin(path = null))))
        stubWalk(source)
        stubIssues()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(listOf(UserDataImportIssueKind.PIN_HAS_NO_MEDIA), kinds())
        assertEquals(1, stored.skippedPins)
        assertEquals(1, stored.issueCount)
        verify(exactly = 0) { imageStore.digest(any(), any()) }
    }

    @Test
    fun `Given a medium the archive does not carry, Then it is reported as missing`() {
        // Given: the entry-name set is the authority on what the archive holds
        val source = FakeArchiveSource(aManifest(), pins = listOf(TestLine(1, aPin())), media = emptyMap())
        stubWalk(source)
        stubIssues()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(listOf(UserDataImportIssueKind.MEDIA_ENTRY_MISSING), kinds())
        assertEquals(ALPHA_PATH, savedIssues.single().subject)
        assertEquals(1, stored.skippedPins)
    }

    @Test
    fun `Given entry paths that traverse or float, Then each is reported and no entry is read`() {
        // Given: the check exists so a malformed archive is reported rather than silently skipped
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                pins =
                    listOf(
                        TestLine(1, aPin(path = "images/..")),
                        TestLine(2, aPin(path = "elsewhere/images/alpha.png")),
                    ),
                media = everyMedium,
            )
        stubWalk(source)
        stubIssues()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(List(2) { UserDataImportIssueKind.ENTRY_PATH_INVALID }, kinds())
        assertEquals(2, stored.skippedPins)
        verify(exactly = 0) { imageStore.digest(any(), any()) }
    }

    @Test
    fun `Given a malformed pin line, Then it is reported and the walk continues`() {
        // Given
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                pins = listOf(TestLine(1, null, failure = "unexpected end of input"), TestLine(2, aPin())),
                media = everyMedium,
            )
        stubWalk(source)
        stubMediaPath()
        stubIssues()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(listOf(UserDataImportIssueKind.LINE_MALFORMED), kinds())
        assertNull(savedIssues.single().subject)
        assertEquals("unexpected end of input", savedIssues.single().detail)
        assertEquals(1, stored.createdPins)
        assertEquals(1, stored.skippedPins)
        assertEquals(2, stored.processedPins)
    }

    @Test
    fun `Given a medium past the byte bound, Then it is reported and nothing is staged`() {
        // Given: the bound is read before a byte is written, which is the ordering this case stands for
        val source = FakeArchiveSource(aManifest(), pins = listOf(TestLine(1, aPin())), media = everyMedium)
        stubWalk(source)
        stubIssues()
        every { imageStore.digest(any(), MAX_IMAGE_BYTES) } throws ImageTooLargeException("over the bound")

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(listOf(UserDataImportIssueKind.MEDIA_TOO_LARGE), kinds())
        assertEquals(1, stored.skippedPins)
        verify(exactly = 0) { imageStore.stage(any(), any()) }
    }

    @Test
    fun `Given a medium past the pixel bound, Then it is reported and the staged file goes`() {
        // Given
        val source = FakeArchiveSource(aManifest(), pins = listOf(TestLine(1, aPin())), media = everyMedium)
        stubWalk(source)
        stubDigest()
        stubHashLookup()
        stubStage()
        stubDiscard()
        stubIssues()
        every { imageProbe.probe(any(), MAX_PIXELS) } throws ImageTooManyPixelsException("too many pixels")

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(listOf(UserDataImportIssueKind.MEDIA_TOO_MANY_PIXELS), kinds())
        assertTrue(stagedPaths.isEmpty())
        assertTrue(promoted.isEmpty())
        assertEquals(1, stored.skippedPins)
    }

    @Test
    fun `Given a medium the probe cannot decode, Then it is reported and the staged file goes`() {
        // Given: a text file renamed .jpg is the shape this arm answers
        val source = FakeArchiveSource(aManifest(), pins = listOf(TestLine(1, aPin())), media = everyMedium)
        stubWalk(source)
        stubDigest()
        stubHashLookup()
        stubStage()
        stubDiscard()
        stubIssues()
        every { imageProbe.probe(any(), MAX_PIXELS) } throws UndecodableImageException("not an image")

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(listOf(UserDataImportIssueKind.MEDIA_UNREADABLE), kinds())
        assertTrue(stagedPaths.isEmpty())
        assertEquals(1, stored.skippedPins)
    }

    @Test
    fun `Given a medium two pins already hold, Then it is reported and nothing is staged`() {
        // Given: nothing binds a medium to at most one pin, so inventing a winner would be arbitrary
        repeat(2) { anExistingImageRow(sha256(alphaBytes)) }
        val source = FakeArchiveSource(aManifest(), pins = listOf(TestLine(1, aPin())), media = everyMedium)
        stubWalk(source)
        stubDigest()
        stubHashLookup()
        stubIssues()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(listOf(UserDataImportIssueKind.MEDIA_AMBIGUOUS), kinds())
        assertEquals(1, stored.skippedPins)
        verify(exactly = 0) { imageStore.stage(any(), any()) }
    }

    @Test
    fun `Given a declared digest that disagrees with the bytes, Then it is reported and the pin is created`() {
        // Given: the only signal an archive was altered in transit, and it changes no outcome
        val lying = aPin().copy(image = ImportedImage(ALPHA_PATH, "0".repeat(HASH_LENGTH)))
        val source = FakeArchiveSource(aManifest(), pins = listOf(TestLine(1, lying)), media = everyMedium)
        stubWalk(source)
        stubMediaPath()
        stubIssues()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then: reporting, not acting; the bytes are the authority
        assertEquals(listOf(UserDataImportIssueKind.MEDIA_DIGEST_MISMATCH), kinds())
        assertEquals(1, savedPins.size)
        assertEquals(1, stored.createdPins)
        assertEquals(0, stored.skippedPins)
        assertEquals(sha256(alphaBytes), savedImages.single().contentHash)
    }

    @Test
    fun `Given pins past the field bounds, Then each is reported invalid and no medium is read`() {
        // Given: the four bounds spec section 4.1 restates for a pin line
        val refs = List(OVER_LONG_REFS) { "t$it" }
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                pins =
                    listOf(
                        TestLine(1, aPin().copy(description = "d".repeat(OVER_LONG_DESCRIPTION))),
                        TestLine(2, aPin().copy(sourceContextUrl = " ")),
                        TestLine(3, aPin(tags = refs)),
                        TestLine(4, aPin(boards = refs)),
                    ),
                media = everyMedium,
            )
        stubWalk(source)
        stubIssues()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(List(4) { UserDataImportIssueKind.FIELD_INVALID }, kinds())
        assertEquals(4, stored.skippedPins)
        verify(exactly = 0) { imageStore.digest(any(), any()) }
    }

    @Test
    fun `Given a source announcing an entry it will not open, Then the line is rejected and the walk goes on`() {
        // Given: the catch-all, so no unenumerated per-line failure can fail a whole import
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                pins = listOf(TestLine(1, aPin()), TestLine(2, aPin(path = BETA_PATH, bytes = betaBytes))),
                media = mapOf(ALPHA_PATH to null, BETA_PATH to betaBytes),
            )
        stubWalk(source)
        stubMediaPath()
        stubIssues()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(listOf(UserDataImportIssueKind.LINE_REJECTED), kinds())
        assertEquals(1, stored.createdPins)
        assertEquals(1, stored.skippedPins)
        assertTrue(savedIssues.single().detail?.contains(ALPHA_PATH) == true)
    }

    @Test
    fun `Given more anomalies than the report holds, Then the rows stop and the count keeps climbing`() {
        // Given
        val detail = "d".repeat(OVER_LONG_DETAIL)
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                pins = (1..REPORT_DETAIL_LIMIT + 1).map { TestLine<ImportedPin>(it, null, failure = detail) },
            )
        stubWalk(source)
        stubIssues()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(REPORT_DETAIL_LIMIT, savedIssues.size)
        assertEquals(REPORT_DETAIL_LIMIT + 1, stored.issueCount)
        assertTrue(stored.issueDetailTruncated)
        assertEquals(detail.take(ISSUE_TEXT_LIMIT), savedIssues.first().detail)
    }

    @Test
    fun `Given fewer anomalies than the report holds, Then nothing is flagged as truncated`() {
        // Given
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                pins = (1..REPORT_DETAIL_LIMIT - 1).map { TestLine<ImportedPin>(it, null, failure = "bad line") },
            )
        stubWalk(source)
        stubIssues()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(REPORT_DETAIL_LIMIT - 1, savedIssues.size)
        assertEquals(REPORT_DETAIL_LIMIT - 1, stored.issueCount)
        assertFalse(stored.issueDetailTruncated)
    }

    @Test
    fun `Given a store that cannot write, Then the failure is rethrown and the row is left alone`() {
        // Given: a full disk is transient, and the retry budget outlasts an operator (spec section 9)
        val source = FakeArchiveSource(aManifest(), pins = listOf(TestLine(1, aPin())), media = everyMedium)
        // No release stub: the row is left RUNNING, so the retry resumes rather than re-uploading
        stubOpen(source)
        every { issueRepository.countForImport(any()) } returns 0
        stubDigest()
        stubHashLookup()
        every { imageStore.stage(any(), MAX_IMAGE_BYTES) } throws IOException("No space left on device")

        // When / Then
        assertThrows(IOException::class.java) { runner.run(importId, isLastAttempt = false, renewLease) }
        assertEquals(UserDataImportState.RUNNING, stored.state)
        assertNull(stored.failureCode)
        assertEquals(0, stored.processedPins)
        assertTrue(promoted.isEmpty())
        assertTrue(stagedPaths.isEmpty())
        verify(exactly = 0) { archiveStore.delete(any()) }
    }
}
