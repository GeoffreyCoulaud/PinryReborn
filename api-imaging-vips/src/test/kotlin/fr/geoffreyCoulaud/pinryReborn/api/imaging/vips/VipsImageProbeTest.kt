package fr.geoffreyCoulaud.pinryReborn.api.imaging.vips

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.ImageFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooManyPixelsException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.StagedFile
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UndecodableImageException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UnsupportedImageFormatException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path

class VipsImageProbeTest {
    private val probe = VipsImageProbe()

    private fun staged(name: String) =
        StagedFile(path = Path.of("src/test/resources/fixtures", name).toString(), byteSize = 0, contentHash = "")

    @Test
    fun `Given a PNG, Then probe returns PNG with its dimensions`() {
        val result = probe.probe(staged("sample.png"), maxPixels = 1_000_000)
        assertEquals(ImageFormat.PNG, result.format)
        assertEquals(10, result.width)
        assertEquals(10, result.height)
    }

    @Test
    fun `Given a JPEG, Then probe returns JPEG`() {
        assertEquals(ImageFormat.JPEG, probe.probe(staged("sample.jpg"), 1_000_000).format)
    }

    @Test
    fun `Given a WebP, Then probe returns WEBP`() {
        assertEquals(ImageFormat.WEBP, probe.probe(staged("sample.webp"), 1_000_000).format)
    }

    @Test
    fun `Given an animated WebP, Then probe accepts it as WEBP`() {
        assertEquals(ImageFormat.WEBP, probe.probe(staged("animated.webp"), 1_000_000).format)
    }

    @Test
    fun `Given an animated GIF, Then probe accepts it as GIF`() {
        assertEquals(ImageFormat.GIF, probe.probe(staged("animated.gif"), 1_000_000).format)
    }

    @Test
    fun `Given a non-image, Then probe throws UndecodableImageException`() {
        assertThrows(UndecodableImageException::class.java) {
            probe.probe(staged("not-an-image.txt"), 1_000_000)
        }
    }

    @Test
    fun `Given an image over the pixel limit, Then probe throws ImageTooManyPixelsException`() {
        assertThrows(ImageTooManyPixelsException::class.java) {
            probe.probe(staged("sample.png"), maxPixels = 1)
        }
    }

    @Test
    fun `Given a TIFF, Then probe throws UnsupportedImageFormatException`() {
        assertThrows(UnsupportedImageFormatException::class.java) {
            probe.probe(staged("sample.tiff"), 1_000_000)
        }
    }
}
