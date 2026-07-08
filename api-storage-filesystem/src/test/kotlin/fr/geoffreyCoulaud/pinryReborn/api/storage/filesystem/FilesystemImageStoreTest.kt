package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooLargeException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class FilesystemImageStoreTest {
    @TempDir lateinit var dataDir: Path

    private fun store() = FilesystemImageStore(dataDir.toString())

    @Test
    fun `Given bytes within the limit, Then stage measures size and hash`() {
        val staged = store().stage(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), maxBytes = 100)
        assertEquals(4, staged.byteSize)
        // SHA-256 of {1,2,3,4}
        assertEquals("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a", staged.contentHash)
        assertTrue(Files.exists(Path.of(staged.path)))
    }

    @Test
    fun `Given bytes over the limit, Then stage throws and leaves no temp file`() {
        val store = store()
        val tmpDir = dataDir.resolve("tmp")
        fun countTmpFiles() = if (Files.exists(tmpDir)) Files.list(tmpDir).use { it.count() } else 0
        val before = countTmpFiles()
        assertThrows(ImageTooLargeException::class.java) {
            store.stage(ByteArrayInputStream(ByteArray(50)), maxBytes = 10)
        }
        val after = countTmpFiles()
        assertEquals(before, after)
    }

    @Test
    fun `Given a staged file, Then promote moves it to the storage key and openStream reads it`() {
        val store = store()
        val staged = store.stage(ByteArrayInputStream(byteArrayOf(9, 9)), maxBytes = 100)
        store.promote(staged, "originals/u/p/img.png")
        assertFalse(Files.exists(Path.of(staged.path)))
        val read = store.openStream("originals/u/p/img.png").use { it.readBytes() }
        assertTrue(read.contentEquals(byteArrayOf(9, 9)))
    }

    @Test
    fun `Given a stored key, Then delete removes it and is idempotent`() {
        val store = store()
        val staged = store.stage(ByteArrayInputStream(byteArrayOf(1)), maxBytes = 100)
        store.promote(staged, "originals/u/p/img.png")
        store.delete("originals/u/p/img.png")
        store.delete("originals/u/p/img.png") // idempotent, must not throw
        assertFalse(Files.exists(dataDir.resolve("originals/u/p/img.png")))
    }

    @Test
    fun `Given a staged file, Then discard removes the temp`() {
        val store = store()
        val staged = store.stage(ByteArrayInputStream(byteArrayOf(1)), maxBytes = 100)
        store.discard(staged)
        assertFalse(Files.exists(Path.of(staged.path)))
    }

    @Test
    fun `Given a storage key with traversal, Then it is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            store().openStream("originals/../../etc/passwd")
        }
    }

    @Test
    fun `Given an absolute storage key, Then it is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            store().openStream("/etc/passwd")
        }
    }
}
