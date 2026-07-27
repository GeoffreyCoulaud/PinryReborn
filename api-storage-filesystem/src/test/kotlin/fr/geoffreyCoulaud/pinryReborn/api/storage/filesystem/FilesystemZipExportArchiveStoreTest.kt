package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveEntryDigest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.zip.ZipFile

class FilesystemZipExportArchiveStoreTest {
    @TempDir lateinit var tempDir: Path

    private val store by lazy { FilesystemZipExportArchiveStore(tempDir.toString()) }

    private fun sha256Hex(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    @Test
    fun `Given entries written to the sink, Then the promoted archive contains them`() {
        // Given
        val staged = store.stage { sink ->
            sink.putTextEntry("README.md", "hello")
            sink.putJsonEntry("manifest.json", mapOf("formatVersion" to 1))
            sink.putJsonLinesEntry("pins.jsonl", sequenceOf(mapOf("id" to "a"), mapOf("id" to "b")))
            sink.putBinaryEntry("images/x.bin", ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        }

        // When
        store.promote(staged, "exports/e1.zip")

        // Then
        ZipFile(tempDir.resolve("exports/e1.zip").toFile()).use { zip ->
            assertEquals("hello", zip.getInputStream(zip.getEntry("README.md")).readBytes().decodeToString())
            assertEquals(
                2,
                zip.getInputStream(zip.getEntry("pins.jsonl")).readBytes().decodeToString().trim().lines().size,
            )
            assertArrayEquals(byteArrayOf(1, 2, 3), zip.getInputStream(zip.getEntry("images/x.bin")).readBytes())
        }
    }

    @Test
    fun `Given a staged archive, Then its size and digest match the file on disk`() {
        // Given / When
        val staged = store.stage { sink -> sink.putTextEntry("a.txt", "hello world") }

        // Then
        val fileBytes = Files.readAllBytes(Path.of(staged.path))
        assertEquals(fileBytes.size.toLong(), staged.byteSize)
        assertEquals(sha256Hex(fileBytes), staged.contentHash)
    }

    @Test
    fun `Given an entry, Then its digest describes the uncompressed content`() {
        // Given: a long, highly-compressible run, so a compressed-byte digest would not match.
        val text = "x".repeat(10_000)
        var entryDigest: ArchiveEntryDigest? = null

        // When
        store.stage { sink -> entryDigest = sink.putTextEntry("big.txt", text) }

        // Then
        val expectedBytes = text.toByteArray()
        assertEquals(expectedBytes.size.toLong(), entryDigest?.byteSize)
        assertEquals(sha256Hex(expectedBytes), entryDigest?.sha256)
    }

    @Test
    fun `Given a failing writer block, Then no temp file is left behind`() {
        // Given / When / Then
        assertThrows(IllegalStateException::class.java) { store.stage { error("boom") } }
        assertTrue(Files.list(tempDir.resolve("tmp")).use { it.findAny().isEmpty })
    }

    @Test
    fun `Given a skip offset, Then the stream starts at that byte`() {
        // Given
        val staged = store.stage { sink -> sink.putTextEntry("a.txt", "hello") }
        store.promote(staged, "exports/skip.zip")
        val fullBytes = Files.readAllBytes(tempDir.resolve("exports/skip.zip"))

        // When
        val read = store.openStream("exports/skip.zip", skipBytes = 4).use { it.readBytes() }

        // Then
        assertArrayEquals(fullBytes.copyOfRange(4, fullBytes.size), read)
    }

    @Test
    fun `Given more entries than the classic ZIP limit, Then the archive is still readable`() {
        // Given / When
        val staged = store.stage { sink -> repeat(65_600) { sink.putTextEntry("e/$it.txt", "x") } }
        store.promote(staged, "exports/big.zip")

        // Then
        ZipFile(tempDir.resolve("exports/big.zip").toFile()).use { assertEquals(65_600, it.size()) }
    }

    @Test
    fun `Given no tmp directory, Then discarding orphaned staged files is a no-op`() {
        // Given: a fresh store whose data dir has never staged anything, so "tmp" does not exist.

        // When
        val removed = store.discardOrphanedStagedFiles(Instant.now())

        // Then
        assertEquals(0, removed)
        assertFalse(Files.exists(tempDir.resolve("tmp")))
    }

    @Test
    fun `Given an old export temp file, Then it is discarded as orphaned`() {
        // Given
        val tmp = Files.createDirectories(tempDir.resolve("tmp"))
        val old = Files.createFile(tmp.resolve("export-old.tmp"))
        val recent = Files.createFile(tmp.resolve("export-recent.tmp"))
        val foreign = Files.createFile(tmp.resolve("stage-image.tmp"))
        Files.setLastModifiedTime(old, FileTime.from(Instant.parse("2026-07-01T00:00:00Z")))

        // When
        val removed = store.discardOrphanedStagedFiles(Instant.parse("2026-07-20T00:00:00Z"))

        // Then
        assertEquals(1, removed)
        assertTrue(Files.exists(recent))
        assertTrue(Files.exists(foreign), "an image store temp must never be swept by the export store")
    }

    @Test
    fun `Given a small requirement, Then there is enough free space`() {
        // Given / When / Then
        assertTrue(store.hasFreeSpace(1))
    }

    @Test
    fun `Given an impossible requirement, Then there is not enough free space`() {
        // Given / When / Then
        assertFalse(store.hasFreeSpace(Long.MAX_VALUE))
    }

    @Test
    fun `Given a staged file, Then discard removes the temp`() {
        // Given
        val staged = store.stage { sink -> sink.putTextEntry("a.txt", "hello") }

        // When
        store.discard(staged)

        // Then
        assertFalse(Files.exists(Path.of(staged.path)))
    }

    @Test
    fun `Given a promoted export, Then delete removes it and is idempotent`() {
        // Given
        val staged = store.stage { sink -> sink.putTextEntry("a.txt", "hello") }
        store.promote(staged, "exports/delete-me.zip")

        // When
        store.delete("exports/delete-me.zip")
        store.delete("exports/delete-me.zip")

        // Then
        assertFalse(Files.exists(tempDir.resolve("exports/delete-me.zip")))
    }

    @Test
    fun `Given the archive format, Then it advertises ZIP media type and extension`() {
        // Given / When / Then
        assertEquals("application/zip", store.format.mediaType)
        assertEquals("zip", store.format.fileExtension)
    }

    @Test
    fun `Given archives on disk, Then forEachStorageKeyOnDisk yields their storage keys`() {
        // Given: two promoted archives plus a staged temp file outside the exports directory
        val exportsDir = Files.createDirectories(tempDir.resolve("exports"))
        Files.createFile(exportsDir.resolve("e1.zip"))
        Files.createFile(exportsDir.resolve("e2.zip"))
        val tmpDir = Files.createDirectories(tempDir.resolve("tmp"))
        Files.createFile(tmpDir.resolve("staged.tmp"))

        // When: the sweep loans the on-disk keys as a lazy sequence
        val yielded = mutableSetOf<String>()
        store.forEachStorageKeyOnDisk { keys -> keys.forEach(yielded::add) }

        // Then: exactly the two archive keys are yielded; the staged temp is not listed
        assertEquals(setOf("exports/e1.zip", "exports/e2.zip"), yielded)
    }
}
