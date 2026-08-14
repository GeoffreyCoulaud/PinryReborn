package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.CursorDirection
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.UserDataImportDtoMapper.toDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class UserDataImportDtoMapperTest {
    private fun awaitingImport() = UserDataImport(
        id = randomUUID(),
        userId = randomUUID(),
        state = UserDataImportState.AWAITING_ARCHIVE,
        requestedAt = Instant.parse("2026-08-14T10:00:00Z"),
    )

    private fun completedImport() = awaitingImport().copy(
        state = UserDataImportState.COMPLETED,
        uploadedBytes = 4096L,
        byteSize = 4096L,
        archiveCompletedAt = Instant.parse("2026-08-14T10:05:00Z"),
        startedAt = Instant.parse("2026-08-14T10:06:00Z"),
        completedAt = Instant.parse("2026-08-14T10:09:00Z"),
        formatVersion = 1,
        announcedPins = 12,
        processedPins = 12,
        createdPins = 9,
        skippedPins = 3,
        createdBoards = 2,
        skippedBoards = 1,
        createdTags = 5,
        skippedTags = 4,
        issueCount = 501,
        issueDetailTruncated = true,
    )

    @Test
    fun `Given an import awaiting its archive, Then toDto carries the state name and leaves the run fields null`() {
        // Given
        val userDataImport = awaitingImport()

        // When
        val dto = userDataImport.toDto()

        // Then
        assertEquals(userDataImport.id, dto.id)
        assertEquals("AWAITING_ARCHIVE", dto.state)
        assertEquals(userDataImport.requestedAt, dto.requestedAt)
        assertEquals(0L, dto.uploadedBytes)
        assertNull(dto.byteSize)
        assertNull(dto.archiveCompletedAt)
        assertNull(dto.startedAt)
        assertNull(dto.completedAt)
        assertNull(dto.formatVersion)
        assertNull(dto.announcedPins)
        assertNull(dto.failureCode)
        assertFalse(dto.issueDetailTruncated)
    }

    @Test
    fun `Given a completed import, Then toDto carries both counters raw and every timestamp`() {
        // Given
        val userDataImport = completedImport()

        // When
        val dto = userDataImport.toDto()

        // Then: the two counters ship raw, with no server-side ratio (spec section 7).
        assertEquals("COMPLETED", dto.state)
        assertEquals(4096L, dto.uploadedBytes)
        assertEquals(4096L, dto.byteSize)
        assertEquals(userDataImport.archiveCompletedAt, dto.archiveCompletedAt)
        assertEquals(userDataImport.startedAt, dto.startedAt)
        assertEquals(userDataImport.completedAt, dto.completedAt)
        assertEquals(1, dto.formatVersion)
        assertEquals(12, dto.announcedPins)
        assertEquals(12, dto.processedPins)
        assertEquals(9, dto.createdPins)
        assertEquals(3, dto.skippedPins)
        assertEquals(2, dto.createdBoards)
        assertEquals(1, dto.skippedBoards)
        assertEquals(5, dto.createdTags)
        assertEquals(4, dto.skippedTags)
    }

    @Test
    fun `Given a report past the detail limit, Then toDto says the detail is truncated`() {
        // Given: a wire assertion only, the behaviour behind the flag is pinned in the runner's tests.
        val userDataImport = completedImport()

        // When
        val dto = userDataImport.toDto()

        // Then
        assertEquals(501, dto.issueCount)
        assertTrue(dto.issueDetailTruncated)
    }

    @Test
    fun `Given a failed import, Then toDto carries the failure code`() {
        // Given
        val userDataImport = awaitingImport().copy(
            state = UserDataImportState.FAILED,
            failureCode = "MANIFEST_MISSING",
        )

        // When
        val dto = userDataImport.toDto()

        // Then
        assertEquals("FAILED", dto.state)
        assertEquals("MANIFEST_MISSING", dto.failureCode)
    }

    @Test
    fun `Given a page with no previous and no next cursor, Then toDto maps both cursors to null`() {
        // Given
        val page = Page(items = listOf(awaitingImport()), previousCursor = null, nextCursor = null)

        // When
        val result = page.toDto()

        // Then
        assertEquals(1, result.imports.size)
        assertNull(result.pagination.previousCursor)
        assertNull(result.pagination.nextCursor)
    }

    @Test
    fun `Given a page with a previous and a next cursor, Then toDto maps both cursors`() {
        // Given
        val previousCursor = Cursor(pivotId = randomUUID(), direction = CursorDirection.BACKWARD)
        val nextCursor = Cursor(pivotId = randomUUID(), direction = CursorDirection.FORWARD)
        val page = Page(
            items = listOf(awaitingImport()),
            previousCursor = previousCursor,
            nextCursor = nextCursor,
        )

        // When
        val result = page.toDto()

        // Then
        assertNotNull(result.pagination.previousCursor)
        assertNotNull(result.pagination.nextCursor)
    }
}
