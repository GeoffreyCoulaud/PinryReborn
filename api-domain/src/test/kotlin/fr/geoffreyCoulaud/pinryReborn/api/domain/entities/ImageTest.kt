package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class ImageTest {
    @Test
    fun `Given image data, Then the entity exposes it and is Identifiable`() {
        // Given
        val id = randomUUID()
        val pinId = randomUUID()
        val now = Instant.parse("2026-07-08T00:00:00Z")
        // When
        val image = Image(
            id = id, pinId = pinId, mimeType = "image/webp",
            width = 800, height = 600, byteSize = 12_345L,
            contentHash = "abc123", storageKey = "originals/u/p/$id.webp", createdAt = now,
        )
        // Then
        assertEquals(id, image.id)
        assertEquals(pinId, image.pinId)
        assertEquals(800, image.width)
        assertEquals("abc123", image.contentHash)
    }
}
