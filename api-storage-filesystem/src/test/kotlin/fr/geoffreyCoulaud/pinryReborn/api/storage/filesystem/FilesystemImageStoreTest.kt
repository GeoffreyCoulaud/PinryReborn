package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooLargeException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
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

    @Test
    fun `Given bytes within the limit, Then digest hashes them and writes nothing`() {
        // Given: a data directory that does not exist yet
        val absent = dataDir.resolve("absent")

        // When
        val hash = FilesystemImageStore(absent.toString())
            .digest(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), maxBytes = 100)

        // Then: the same hash stage measures, and not a directory created. `stage(...).also { discard(it) }`
        // returns the same hash and deletes its temp file, but leaves `tmp/` behind, so this is what
        // discriminates; a listing before and after would not.
        assertEquals("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a", hash)
        assertFalse(Files.exists(absent))
    }

    @Test
    fun `Given a data directory that cannot be created, Then digest still hashes`() {
        // Given: a regular file where the data directory would be, so createDirectories fails for
        // every user, root included. An unwritable directory would not bite when tests run as root.
        val blocked = dataDir.resolve("blocked")
        Files.writeString(blocked, "not a directory")

        // When
        val hash = FilesystemImageStore(blocked.toString())
            .digest(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), maxBytes = 100)

        // Then
        assertEquals("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a", hash)
    }

    @Test
    fun `Given bytes over the limit, Then digest throws before the stream is exhausted`() {
        // Given: a stream that refuses to be read past its first buffer
        val source = PoisonedStream(readableBytes = 8192)

        // When / Then: the bound is what stops it, not the poison
        assertThrows(ImageTooLargeException::class.java) {
            store().digest(source, maxBytes = 100)
        }
    }

    /**
     * Delivers [readableBytes] and then throws, so "the refusal arrives before the stream is
     * exhausted" is observable rather than merely unmeasured.
     */
    private class PoisonedStream(private val readableBytes: Int) : InputStream() {
        private var delivered = 0

        override fun read(): Int = throw IOException("the single-byte path is not the one under test")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (delivered >= readableBytes) throw IOException("read past the poisoned point")
            val count = minOf(length, readableBytes - delivered)
            delivered += count
            return count
        }
    }
}
