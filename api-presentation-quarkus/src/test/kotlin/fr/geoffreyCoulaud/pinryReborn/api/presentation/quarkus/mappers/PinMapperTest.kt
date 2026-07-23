package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.CursorDirection
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.PinMapper.toDto
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class PinMapperTest {
    private fun createPin(): Pin =
        Pin(
            id = randomUUID(),
            author = User(id = randomUUID(), name = createRandomString(), createdAt = Instant.now()),
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = createRandomString(),
            tags = emptyList(),
            boards = emptyList(),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `Given a page with no previous and no next cursor, Then toDto maps both cursors to null`() {
        // Given
        val page = Page<Pin>(items = listOf(createPin()), previousCursor = null, nextCursor = null)

        // When
        val result = page.toDto()

        // Then
        assertNull(result.pagination.previousCursor)
        assertNull(result.pagination.nextCursor)
    }

    @Test
    fun `Given a page with a previous and a next cursor, Then toDto maps both cursors`() {
        // Given
        val previousCursor = Cursor(pivotId = randomUUID(), direction = CursorDirection.BACKWARD)
        val nextCursor = Cursor(pivotId = randomUUID(), direction = CursorDirection.FORWARD)
        val page = Page<Pin>(items = listOf(createPin()), previousCursor = previousCursor, nextCursor = nextCursor)

        // When
        val result = page.toDto()

        // Then
        assertNotNull(result.pagination.previousCursor)
        assertNotNull(result.pagination.nextCursor)
    }
}
