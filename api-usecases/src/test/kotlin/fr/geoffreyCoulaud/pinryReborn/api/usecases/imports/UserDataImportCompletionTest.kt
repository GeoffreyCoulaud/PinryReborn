package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.exceptions.PermanentTaskException
import io.mockk.every
import io.mockk.verify
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Steps 7 and 8 of spec section 8: the compare-and-set that publishes, the catch-all that marks an
 * unenumerated failure, and the archive's fate on each. Split off for `LargeClass`, as the pin walk was.
 */
internal class UserDataImportCompletionTest : UserDataImportRunnerFixtures() {
    @Test
    fun `Given a walk that reaches the end, Then the row is completed and the archive it opened is deleted`() {
        // Given: a key no derivation would produce, so the deletion is pinned to the column the row carries
        val promotedElsewhere = "imports/promoted-elsewhere.zip"
        val source =
            FakeArchiveSource(
                manifest = aManifest(),
                tags = listOf(TestLine(1, ImportedTag(name = "voyage", createdAt = pastInstant))),
            )
        stubWalk(source, anImport(UserDataImportState.PENDING, storageKey = promotedElsewhere))
        stubTagLookup()
        stubTagCreation()
        stubArchiveRelease()

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then
        assertEquals(UserDataImportState.COMPLETED, stored.state)
        assertEquals(now, stored.completedAt)
        assertEquals(1, stored.createdTags)
        verify { archiveStore.open(promotedElsewhere) }
        assertEquals(listOf(promotedElsewhere), deletedArchives)
    }

    @Test
    fun `Given a cancellation landing before the completion, Then nothing is published and the bytes go`() {
        // Given: one pin settles, then the canceller writes, and step 7 reads the row it left
        val source =
            FakeArchiveSource(manifest = aManifest(), pins = listOf(TestLine(1, aPin())), media = everyMedium)
        stubWalk(source)
        stubMediaPath()
        stubArchiveRelease()
        cancelWhen { savedPins.isNotEmpty() }

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then: the pin stays, since an import is not a transaction, and the runner takes the archive
        assertEquals(UserDataImportState.CANCELLED, stored.state)
        assertNull(stored.completedAt)
        assertEquals(1, savedPins.size)
        assertEquals(listOf(storageKey), deletedArchives)
    }

    @Test
    fun `Given the import row deleted before the completion, Then nothing is published and the bytes go`() {
        // Given: an account deletion lands while the walk holds the archive
        val source = FakeArchiveSource(manifest = aManifest(), boards = listOf(TestLine(1, aBoard("Summer"))))
        stubWalk(source)
        stubBoardLookup()
        stubBoardCreation()
        stubArchiveRelease()
        reread = { row -> row.takeIf { stored.createdBoards == 0 } }

        // When
        runner.run(importId, isLastAttempt = false, renewLease)

        // Then: the board walk's write is the last one, so no COMPLETED was written over an absent row
        assertEquals(UserDataImportState.RUNNING, stored.state)
        assertEquals(1, savedBoards.size)
        assertEquals(listOf(storageKey), deletedArchives)
    }

    @Test
    fun `Given an unexpected failure on the last attempt, Then the row is marked failed and the throw escapes`() {
        // Given: without this the row stays RUNNING for ever, holding the account's only import slot
        val source =
            FakeArchiveSource(manifest = aManifest(), pins = listOf(TestLine(1, aPin())), media = everyMedium)
        stubWalk(source)
        stubDigest()
        stubHashLookup()
        stubArchiveRelease()
        every { imageStore.stage(any(), MAX_IMAGE_BYTES) } throws IOException("No space left on device")

        // When / Then: rethrown, so the queue still counts the attempt and dead-letters the task
        assertThrows(IOException::class.java) { runner.run(importId, isLastAttempt = true, renewLease) }
        assertEquals(UserDataImportState.FAILED, stored.state)
        assertEquals("IMPORT_FAILED", stored.failureCode)
        assertEquals(0, stored.processedPins)
        assertEquals(listOf(storageKey), deletedArchives)
    }

    @Test
    fun `Given an archive refused on the last attempt, Then the catch-all leaves its failure code alone`() {
        // Given: a permanent refusal has already marked the row, which is what the fence reads
        stubOpen(FakeArchiveSource(manifest = null))
        stubArchiveRelease()

        // When / Then
        assertThrows(PermanentTaskException::class.java) { runner.run(importId, isLastAttempt = true, renewLease) }
        assertEquals(UserDataImportState.FAILED, stored.state)
        assertEquals("MANIFEST_MISSING", stored.failureCode)
        assertEquals(listOf(storageKey), deletedArchives)
    }
}
