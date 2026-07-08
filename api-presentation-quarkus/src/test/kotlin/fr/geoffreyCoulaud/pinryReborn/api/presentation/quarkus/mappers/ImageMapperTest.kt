package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.ImageMapper.toDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class ImageMapperTest {
    @Test
    fun `Given an image and base url, Then toDto builds the serve url`() {
        val pinId = randomUUID()
        val image = Image(randomUUID(), pinId, "image/webp", 8, 6, 99, "h", "originals/x/y/z.webp", Instant.EPOCH)
        val dto = image.toDto("https://host")
        assertEquals("https://host/api/v1/pins/$pinId/image", dto.url)
        assertEquals("image/webp", dto.mimeType)
        assertEquals(8, dto.width)
    }
}
