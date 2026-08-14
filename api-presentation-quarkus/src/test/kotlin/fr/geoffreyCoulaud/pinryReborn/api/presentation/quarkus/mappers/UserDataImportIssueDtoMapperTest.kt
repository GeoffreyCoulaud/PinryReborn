package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImportIssue
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.CursorDirection
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportIssueKind
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.UserDataImportIssueDtoMapper.toDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class UserDataImportIssueDtoMapperTest {
    private fun anIssue() = UserDataImportIssue(
        id = randomUUID(),
        importId = randomUUID(),
        kind = UserDataImportIssueKind.MEDIA_DIGEST_MISMATCH,
        line = 42,
        subject = "images/a1b2.jpg",
        detail = "declared sha256 does not match the bytes",
    )

    @Test
    fun `Given an issue, Then toDto carries the kind name, the line, the subject and the detail`() {
        // Given
        val issue = anIssue()

        // When
        val dto = issue.toDto()

        // Then
        assertEquals(issue.id, dto.id)
        assertEquals("MEDIA_DIGEST_MISMATCH", dto.kind)
        assertEquals(42, dto.line)
        assertEquals("images/a1b2.jpg", dto.subject)
        assertEquals("declared sha256 does not match the bytes", dto.detail)
    }

    @Test
    fun `Given an issue carrying no line, subject or detail, Then toDto leaves the three null`() {
        // Given
        val issue = anIssue().copy(line = null, subject = null, detail = null)

        // When
        val dto = issue.toDto()

        // Then
        assertNull(dto.line)
        assertNull(dto.subject)
        assertNull(dto.detail)
    }

    @Test
    fun `Given a page with no previous and no next cursor, Then toDto maps both cursors to null`() {
        // Given
        val page = Page(items = listOf(anIssue()), previousCursor = null, nextCursor = null)

        // When
        val result = page.toDto()

        // Then
        assertEquals(1, result.issues.size)
        assertNull(result.pagination.previousCursor)
        assertNull(result.pagination.nextCursor)
    }

    @Test
    fun `Given a page with a previous and a next cursor, Then toDto maps both cursors`() {
        // Given
        val previousCursor = Cursor(pivotId = randomUUID(), direction = CursorDirection.BACKWARD)
        val nextCursor = Cursor(pivotId = randomUUID(), direction = CursorDirection.FORWARD)
        val page = Page(items = listOf(anIssue()), previousCursor = previousCursor, nextCursor = nextCursor)

        // When
        val result = page.toDto()

        // Then
        assertNotNull(result.pagination.previousCursor)
        assertNotNull(result.pagination.nextCursor)
    }
}
