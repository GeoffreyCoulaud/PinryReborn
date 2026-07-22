package fr.geoffreyCoulaud.pinryReborn.api.imaging.vips

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.ImageFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ProbeResult
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionSpec
import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class VipsImageTransformerTest {
    private val transformer = VipsImageTransformer(quality = 80)

    private fun renderAndProbe(fixture: String, spec: RenditionSpec): ProbeResult {
        val staged = Files.newInputStream(Path.of("src/test/resources/fixtures", fixture)).use {
            transformer.render(it, spec)
        }
        return try {
            VipsImageProbe().probe(StagedFile(staged.path, 0, ""), maxPixels = 1_000_000)
        } finally {
            Files.deleteIfExists(Path.of(staged.path))
        }
    }

    @Test
    fun `Given a static image and a smaller size, Then it downscales to WebP with that shortest side`() {
        val result = renderAndProbe("sample.png", RenditionSpec(shortestSide = 4, animated = false))
        assertEquals(ImageFormat.WEBP, result.format)
        assertEquals(4, minOf(result.width, result.height))
        assertFalse(result.animated)
    }

    @Test
    fun `Given a size equal to the native shortest side, Then it re-encodes WebP without upscaling`() {
        val result = renderAndProbe("sample.png", RenditionSpec(shortestSide = 10, animated = false))
        assertEquals(ImageFormat.WEBP, result.format)
        assertEquals(10, minOf(result.width, result.height))
    }

    @Test
    fun `Given an animated source with animated = true, Then it downscales and keeps the animation`() {
        val result = renderAndProbe("animated.gif", RenditionSpec(shortestSide = 4, animated = true))
        assertEquals(ImageFormat.WEBP, result.format)
        assertEquals(4, minOf(result.width, result.height))
        assertTrue(result.animated)
    }

    @Test
    fun `Given an animated source with animated = false, Then it flattens to a static WebP`() {
        val result = renderAndProbe("animated.gif", RenditionSpec(shortestSide = 4, animated = false))
        assertEquals(ImageFormat.WEBP, result.format)
        assertEquals(4, minOf(result.width, result.height))
        assertFalse(result.animated)
    }
}
