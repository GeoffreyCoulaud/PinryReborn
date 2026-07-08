package fr.geoffreyCoulaud.pinryReborn.api.domain.enums

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImageFormatTest {
    @Test
    fun `Given each format, Then it exposes its mime type and extension`() {
        assertEquals("image/png" to "png", ImageFormat.PNG.mimeType to ImageFormat.PNG.extension)
        assertEquals("image/jpeg" to "jpg", ImageFormat.JPEG.mimeType to ImageFormat.JPEG.extension)
        assertEquals("image/webp" to "webp", ImageFormat.WEBP.mimeType to ImageFormat.WEBP.extension)
        assertEquals("image/gif" to "gif", ImageFormat.GIF.mimeType to ImageFormat.GIF.extension)
    }
}
