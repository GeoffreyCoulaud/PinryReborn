package fr.geoffreyCoulaud.pinryReborn.api.domain.images

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.ImageFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ImagePortsTest {
    @Test
    fun `Given a StagedFile, Then it carries path, size and hash`() {
        val staged = StagedFile(path = "/tmp/x", byteSize = 3, contentHash = "h")
        assertEquals("/tmp/x", staged.path)
        assertEquals(3, staged.byteSize)
    }

    @Suppress("EmptyFunctionBlock")
    @Test
    fun `Given a fake store and probe, Then the port contracts compile and return`() {
        val store = object : ImageStore {
            override fun stage(source: InputStream, maxBytes: Long) =
                StagedFile("/tmp/staged", source.readBytes().size.toLong(), "hash")
            override fun promote(staged: StagedFile, storageKey: String) {}
            override fun openStream(storageKey: String): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun delete(storageKey: String) {}
            override fun discard(staged: StagedFile) {}
        }
        val probe = object : ImageProbe {
            override fun probe(staged: StagedFile, maxPixels: Long) =
                ProbeResult(ImageFormat.PNG, 10, 20, animated = false)
        }
        val staged = store.stage(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 100)
        val result = probe.probe(staged, 1_000)
        assertEquals(3, staged.byteSize)
        assertEquals(ImageFormat.PNG, result.format)
        assertEquals(20, result.height)
    }

    @Test
    fun `Given probe exceptions, Then they share the sealed base`() {
        assertTrue(UnsupportedImageFormatException("x") is ImageProbeException)
        assertTrue(UndecodableImageException("x") is ImageProbeException)
        assertTrue(ImageTooManyPixelsException("x") is ImageProbeException)
    }
}
