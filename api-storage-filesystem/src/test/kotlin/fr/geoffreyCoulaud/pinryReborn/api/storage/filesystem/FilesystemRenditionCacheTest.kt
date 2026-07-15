package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import fr.geoffreyCoulaud.pinryReborn.api.domain.images.StagedFile
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class FilesystemRenditionCacheTest {
    @TempDir lateinit var dataDir: Path

    private fun cache() = FilesystemRenditionCache(dataDir.toString())

    private fun staged(bytes: ByteArray): StagedFile {
        val tmp = Files.createTempFile("staged-", ".webp")
        Files.write(tmp, bytes)
        return StagedFile(tmp.toString(), bytes.size.toLong(), "h")
    }

    @Test
    fun `Given a stored rendition, Then openStream reads it back`() {
        val id = UUID.randomUUID()
        cache().store(id, "4-a.webp", staged(byteArrayOf(1, 2, 3)))
        val read = cache().openStream(id, "4-a.webp")!!.use { it.readBytes() }
        assertArrayEquals(byteArrayOf(1, 2, 3), read)
    }

    @Test
    fun `Given no rendition, Then openStream returns null`() {
        assertNull(cache().openStream(UUID.randomUUID(), "4-a.webp"))
    }

    @Test
    fun `Given cached renditions for an image, Then evictImage removes the whole subtree`() {
        val id = UUID.randomUUID()
        cache().store(id, "4-a.webp", staged(byteArrayOf(1)))
        cache().store(id, "8-s.webp", staged(byteArrayOf(2)))
        cache().evictImage(id)
        assertFalse(Files.exists(dataDir.resolve("cache/$id")))
        assertNull(cache().openStream(id, "4-a.webp"))
    }

    @Test
    fun `Given no cache subtree, Then evictImage is a no-op`() {
        cache().evictImage(UUID.randomUUID()) // must not throw
    }

    @Test
    fun `Given a traversal key, Then it is rejected`() {
        // Given / When / Then: a key that escapes the data dir root is rejected.
        // Enough `..` to climb past the temp-dir depth to the filesystem root, so the
        // resolved path lands outside <dataDir> and the containment guard rejects it.
        assertThrows(IllegalArgumentException::class.java) {
            cache().openStream(UUID.randomUUID(), "../".repeat(20) + "etc/passwd")
        }
    }
}
