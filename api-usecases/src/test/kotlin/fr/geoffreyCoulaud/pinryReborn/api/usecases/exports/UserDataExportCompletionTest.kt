package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The tail of a build: the promote, the fence publishing it, and the net covering both (spec sections
 * 4.1 and 4.2). Read off the fake store, so the canonical key is what the disk holds afterwards.
 */
internal class UserDataExportCompletionTest : UserDataExportFakeStoreFixtures() {

    @Test
    fun `Given a successful build, Then the row carries size, digest, media type and extension`() {
        // Given
        stubFakeStoreBuild()

        // When
        fakeStoreBuilder.build(exportId, isLastAttempt = false, renewLease = {})

        // Then
        val published = requireNotNull(stored())
        assertEquals(UserDataExportState.READY, published.state)
        assertEquals(storageKey, published.storageKey)
        assertEquals(stagedByteSize, published.byteSize)
        assertEquals(stagedHash, published.sha256)
        assertEquals("application/zip", published.mediaType)
        assertEquals("zip", published.fileExtension)
        assertEquals(now, published.completedAt)
        assertEquals(now.plus(retention), published.expiresAt)
        assertEquals(listOf(storageKey), fakeArchiveStore.promoted.keys.toList())
        assertEquals(stagedHash, fakeArchiveStore.promoted.getValue(storageKey).contentHash)
    }

    @Test
    fun `Given another attempt that published first, Then the canonical key keeps the bytes it holds`() {
        // Given: the rival lands on the fence's own re-read, keyed on the staging this attempt did,
        // so it arrives after this one staged and before it promotes. Both read a legitimate PENDING.
        stubFakeStoreBuild()
        val rivalStaged = StagedFile(path = "tmp/rival.zip", byteSize = 4096L, contentHash = "rival-sha256")
        rivalPublishesWhen({ fakeArchiveStore.stagings > 0 }, rivalStaged)

        // When
        fakeStoreBuilder.build(exportId, isLastAttempt = true, renewLease = {})

        // Then: the winner's bytes are still there, and the row still declares that same archive
        assertEquals(rivalStaged, fakeArchiveStore.promoted[storageKey])
        assertEquals(rivalStaged.contentHash, stored()?.sha256)
        assertEquals(rivalStaged.byteSize, stored()?.byteSize)
        assertEquals(listOf(fakeArchiveStore.staged), fakeArchiveStore.discarded)
        assertEquals(emptyList<String>(), fakeArchiveStore.deleted)
    }

    @Test
    fun `Given an export cancelled before it is published, Then nothing is promoted and the file is discarded`() {
        // Given: the DELETE lands after the archive is staged, which is the window the fence covers
        stubFakeStoreBuild()
        deleteWhen { fakeArchiveStore.stagings > 0 }

        // When
        fakeStoreBuilder.build(exportId, isLastAttempt = false, renewLease = {})

        // Then
        assertEquals(UserDataExportState.DELETED, stored()?.state)
        assertTrue(fakeArchiveStore.promoted.isEmpty(), "a refused attempt promotes nothing")
        assertEquals(listOf(fakeArchiveStore.staged), fakeArchiveStore.discarded)
        assertEquals(emptyList<String>(), fakeArchiveStore.deleted)
    }

    @Test
    fun `Given the export row gone before it is published, Then nothing is promoted and the file is discarded`() {
        // Given: the row was hard-deleted between staging and publishing (account deletion), so the
        // re-read inside the transaction finds nothing at all, not merely a non-PENDING row.
        stubFakeStoreBuild()
        eraseWhen { fakeArchiveStore.stagings > 0 }

        // When
        fakeStoreBuilder.build(exportId, isLastAttempt = false, renewLease = {})

        // Then
        assertNull(stored())
        assertTrue(fakeArchiveStore.promoted.isEmpty(), "a refused attempt promotes nothing")
        assertEquals(listOf(fakeArchiveStore.staged), fakeArchiveStore.discarded)
        assertEquals(emptyList<String>(), fakeArchiveStore.deleted)
    }

    @Test
    fun `Given a published export, Then the promote ran in the transaction that read the row and wrote it`() {
        // Given: the complement of the case above, and only that. This number cannot see the order
        // inside the transaction, so an attempt promoting before its fence would satisfy it too.
        stubFakeStoreBuild()

        // When
        fakeStoreBuilder.build(exportId, isLastAttempt = false, renewLease = {})

        // Then
        val publishing = writtenInTransactions.last()
        assertNotNull(publishing, "the row should be published inside a transaction")
        assertEquals(publishing, readInTransactions.last())
        assertEquals(listOf(publishing), fakeArchiveStore.promotedInTransactions)
    }

    @Test
    fun `Given a promote that throws on the last attempt, Then the export is FAILED and the file is discarded`() {
        // Given
        stubFakeStoreBuild()
        fakeArchiveStore.beforePromote = { error("the archive could not be promoted") }

        // When / Then: the failure still reaches the queue, and the row no longer stays PENDING
        assertThrows(IllegalStateException::class.java) {
            fakeStoreBuilder.build(exportId, isLastAttempt = true, renewLease = {})
        }
        assertEquals(UserDataExportState.FAILED, stored()?.state)
        assertEquals("BUILD_FAILED", stored()?.failureCode)
        assertTrue(fakeArchiveStore.promoted.isEmpty(), "a promote that threw left no archive")
        assertEquals(listOf(fakeArchiveStore.staged), fakeArchiveStore.discarded)
    }

    @Test
    fun `Given a promote that throws on an earlier attempt, Then the export stays PENDING and the file is discarded`() {
        // Given
        stubFakeStoreBuild()
        fakeArchiveStore.beforePromote = { error("the archive could not be promoted") }

        // When / Then
        assertThrows(IllegalStateException::class.java) {
            fakeStoreBuilder.build(exportId, isLastAttempt = false, renewLease = {})
        }
        assertEquals(UserDataExportState.PENDING, stored()?.state)
        assertEquals(listOf(fakeArchiveStore.staged), fakeArchiveStore.discarded)
    }

    @Test
    fun `Given a row write that throws on the last attempt, Then the export is FAILED and the file is discarded`() {
        // Given: only the READY write fails, so the marking that follows it still lands. The promote
        // stands: this runner never rolls back, and that residue is what pass 3 reclaims (ADR 0017).
        stubFakeStoreBuild()
        beforeWrite = { row -> if (row.state == UserDataExportState.READY) error("the row could not be published") }

        // When / Then
        assertThrows(IllegalStateException::class.java) {
            fakeStoreBuilder.build(exportId, isLastAttempt = true, renewLease = {})
        }
        assertEquals(UserDataExportState.FAILED, stored()?.state)
        assertEquals("BUILD_FAILED", stored()?.failureCode)
        assertEquals(listOf(fakeArchiveStore.staged), fakeArchiveStore.discarded)
    }
}
