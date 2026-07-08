package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.ImageModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.ImageModelMapper.toModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class ImageModelMapperTest {
    @Test
    fun `Given an image, Then toModel and toDomain round-trip its fields`() {
        // Given
        val image = Image(
            id = randomUUID(), pinId = randomUUID(), mimeType = "image/png",
            width = 4, height = 5, byteSize = 6, contentHash = "h",
            storageKey = "originals/a/b/c.png", createdAt = Instant.parse("2026-07-08T00:00:00Z"),
        )
        // When
        val roundTripped = image.toModel().toDomain()
        // Then
        assertEquals(image, roundTripped)
    }
}
