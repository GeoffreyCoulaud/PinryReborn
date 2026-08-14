package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveBoundExceededException
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveLine
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveSource
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveTooLargeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportChunkOffsetMismatchException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID.randomUUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class FilesystemZipImportArchiveStoreTest {
    @TempDir lateinit var tempDir: Path

    private val store by lazy { FilesystemZipImportArchiveStore(tempDir.toString(), MAX_LINE_BYTES) }

    private fun sha256Hex(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun chunk(text: String) = ByteArrayInputStream(text.toByteArray())

    // --- chunked upload ---

    @Test
    fun `Given sequential chunks, Then they concatenate into one archive`() {
        // Given
        val importId = randomUUID()

        // When
        val afterFirst = store.appendChunk(importId, offset = 0, bytes = chunk("hello "), maxTotalBytes = 1_000)
        val afterSecond =
            store.appendChunk(importId, offset = afterFirst, bytes = chunk("world"), maxTotalBytes = 1_000)
        val staged = store.finishUpload(importId)

        // Then
        assertEquals(6, afterFirst)
        assertEquals(11, afterSecond)
        assertEquals("hello world", Files.readString(Path.of(staged.path)))
    }

    @Test
    fun `Given a replayed offset, Then the chunk is refused and carries the current length`() {
        // Given
        val importId = randomUUID()
        store.appendChunk(importId, offset = 0, bytes = chunk("hello "), maxTotalBytes = 1_000)

        // When
        val refusal =
            assertThrows(ImportChunkOffsetMismatchException::class.java) {
                store.appendChunk(importId, offset = 0, bytes = chunk("hello "), maxTotalBytes = 1_000)
            }

        // Then: the client resumes from what the store reports rather than restarting
        assertEquals(6, refusal.currentLength)
    }

    @Test
    fun `Given an offset past the end, Then the chunk is refused and carries the current length`() {
        // Given
        val importId = randomUUID()
        store.appendChunk(importId, offset = 0, bytes = chunk("hello "), maxTotalBytes = 1_000)

        // When
        val refusal =
            assertThrows(ImportChunkOffsetMismatchException::class.java) {
                store.appendChunk(importId, offset = 99, bytes = chunk("world"), maxTotalBytes = 1_000)
            }

        // Then
        assertEquals(6, refusal.currentLength)
    }

    @Test
    fun `Given a first chunk for an unknown import, Then offset zero is the only one accepted`() {
        // Given / When
        val refusal =
            assertThrows(ImportChunkOffsetMismatchException::class.java) {
                store.appendChunk(randomUUID(), offset = 5, bytes = chunk("x"), maxTotalBytes = 1_000)
            }

        // Then
        assertEquals(0, refusal.currentLength)
    }

    @Test
    fun `Given a chunk carrying the total past the maximum, Then it is refused and the length is unchanged`() {
        // Given
        val importId = randomUUID()
        val afterFirst = store.appendChunk(importId, offset = 0, bytes = chunk("hello "), maxTotalBytes = 10)

        // When
        assertThrows(ImportArchiveTooLargeException::class.java) {
            store.appendChunk(importId, offset = afterFirst, bytes = chunk("world"), maxTotalBytes = 10)
        }

        // Then: the refused bytes left nothing behind, so the client resumes rather than restarts
        val resumed = store.appendChunk(importId, offset = afterFirst, bytes = chunk("!"), maxTotalBytes = 10)
        assertEquals(7, resumed)
    }

    @Test
    fun `Given a first chunk larger than the maximum, Then it is refused and nothing is uploaded`() {
        // Given
        val importId = randomUUID()

        // When
        assertThrows(ImportArchiveTooLargeException::class.java) {
            store.appendChunk(importId, offset = 0, bytes = chunk("hello world"), maxTotalBytes = 4)
        }

        // Then
        assertEquals(4, store.appendChunk(importId, offset = 0, bytes = chunk("abcd"), maxTotalBytes = 4))
    }

    @Test
    fun `Given a finished upload, Then its size and digest describe the bytes on disk`() {
        // Given
        val importId = randomUUID()
        val content = "an archive".toByteArray()
        store.appendChunk(importId, offset = 0, bytes = ByteArrayInputStream(content), maxTotalBytes = 1_000)

        // When
        val staged = store.finishUpload(importId)

        // Then
        assertEquals(content.size.toLong(), staged.byteSize)
        assertEquals(sha256Hex(content), staged.contentHash)
    }

    @Test
    fun `Given a finished upload, Then promoting it moves the bytes under their storage key`() {
        // Given
        val importId = randomUUID()
        store.appendChunk(importId, offset = 0, bytes = chunk("an archive"), maxTotalBytes = 1_000)
        val staged = store.finishUpload(importId)
        val storageKey = "imports/$importId.zip"

        // When
        store.promote(staged, storageKey)

        // Then
        assertEquals("an archive", Files.readString(tempDir.resolve(storageKey)))
        assertFalse(Files.exists(Path.of(staged.path)))
    }

    @Test
    fun `Given an upload in flight, Then discarding it removes the partial bytes`() {
        // Given
        val importId = randomUUID()
        store.appendChunk(importId, offset = 0, bytes = chunk("half of it"), maxTotalBytes = 1_000)

        // When
        store.discardPartialUpload(importId)

        // Then: the next chunk starts from nothing, so a cancelled upload frees its slot
        assertEquals(1, store.appendChunk(importId, offset = 0, bytes = chunk("x"), maxTotalBytes = 1_000))
    }

    @Test
    fun `Given no upload for an import, Then discarding it is a no-op`() {
        // Given / When / Then
        store.discardPartialUpload(randomUUID())
    }

    // --- staged file retention ---

    @Test
    fun `Given no tmp directory, Then discarding orphaned staged files is a no-op`() {
        // Given / When
        val discarded = store.discardOrphanedStagedFiles(Instant.parse("2026-08-14T10:00:00Z"))

        // Then
        assertEquals(0, discarded)
    }

    @Test
    fun `Given an old partial upload, Then it is discarded as orphaned`() {
        // Given
        val importId = randomUUID()
        store.appendChunk(importId, offset = 0, bytes = chunk("abandoned"), maxTotalBytes = 1_000)
        val uploads = Files.list(tempDir.resolve("tmp")).use { it.toList() }
        uploads.forEach { Files.setLastModifiedTime(it, FileTime.from(Instant.parse("2026-08-01T00:00:00Z"))) }

        // When
        val discarded = store.discardOrphanedStagedFiles(Instant.parse("2026-08-14T10:00:00Z"))

        // Then
        assertEquals(1, discarded)
        assertEquals(0, Files.list(tempDir.resolve("tmp")).use { it.count() })
    }

    @Test
    fun `Given a recent partial upload, Then it survives the orphan sweep`() {
        // Given
        val importId = randomUUID()
        store.appendChunk(importId, offset = 0, bytes = chunk("still streaming"), maxTotalBytes = 1_000)

        // When
        val discarded = store.discardOrphanedStagedFiles(Instant.parse("2026-08-01T00:00:00Z"))

        // Then
        assertEquals(0, discarded)
    }

    // --- free space, deletion, sweeping ---

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
    fun `Given a promoted archive, Then delete removes it and is idempotent`() {
        // Given
        val storageKey = promoteArchive("imports/one.zip") { it.putNextEntry(ZipEntry("a.txt")) }

        // When
        store.delete(storageKey)
        store.delete(storageKey)

        // Then
        assertFalse(Files.exists(tempDir.resolve(storageKey)))
    }

    @Test
    fun `Given archives on disk, Then forEachStorageKeyOnDisk yields their keys and never an upload in flight`() {
        // Given: an upload in flight, which the sweep must not read as a promoted archive
        val storageKey = promoteArchive("imports/promoted.zip") { it.putNextEntry(ZipEntry("a.txt")) }
        store.appendChunk(randomUUID(), offset = 0, bytes = chunk("in flight"), maxTotalBytes = 1_000)

        // When
        var keys = emptyList<String>()
        store.forEachStorageKeyOnDisk { keys = it.toList() }

        // Then
        assertEquals(listOf(storageKey), keys)
    }

    @Test
    fun `Given no imports directory on disk, Then forEachStorageKeyOnDisk yields nothing and does not throw`() {
        // Given: a fresh install, where the sweep runs before any archive was ever promoted
        var ran = false
        var keys = emptyList<String>()

        // When
        store.forEachStorageKeyOnDisk {
            ran = true
            keys = it.toList()
        }

        // Then: the loan contract holds, the block runs exactly once
        assertTrue(ran)
        assertTrue(keys.isEmpty())
    }

    // --- reading an archive ---

    @Test
    fun `Given an archive, Then entryNames lists every entry it holds`() {
        // Given
        val storageKey =
            promoteArchive("imports/named.zip") { zip ->
                writeEntry(zip, "manifest.json", "{}")
                writeEntry(zip, "pins.jsonl", "")
            }

        // When
        val names = store.open(storageKey).use { it.entryNames(MANY_ENTRIES) }

        // Then
        assertEquals(setOf("manifest.json", "pins.jsonl"), names)
    }

    @Test
    fun `Given more entries than the bound, Then the archive is refused`() {
        // Given: no early-stop channel exists here, since the central directory is read when the ZIP
        // opens. The criterion is the refusal itself, and nothing more is claimed.
        val storageKey =
            promoteArchive("imports/crowded.zip") { zip ->
                repeat(3) { writeEntry(zip, "entry$it.txt", "x") }
            }

        // When / Then
        store.open(storageKey).use { source ->
            assertThrows(ArchiveBoundExceededException::class.java) { source.entryNames(2) }
        }
    }

    @Test
    fun `Given a JSON entry, Then readJson binds it to a Kotlin type`() {
        // Given
        val storageKey =
            promoteArchive("imports/manifest.zip") { zip ->
                writeEntry(zip, "manifest.json", """{"formatVersion":1,"generator":"pinry-reborn"}""")
            }

        // When
        val manifest =
            store.open(storageKey).use { it.readJson("manifest.json", ManifestFixture::class.java, 1_000) }

        // Then
        assertEquals(ManifestFixture(formatVersion = 1, generator = "pinry-reborn"), manifest)
    }

    @Test
    fun `Given an absent entry, Then readJson returns null`() {
        // Given
        val storageKey = promoteArchive("imports/empty.zip") { writeEntry(it, "other.json", "{}") }

        // When
        val manifest =
            store.open(storageKey).use { it.readJson("manifest.json", ManifestFixture::class.java, 1_000) }

        // Then
        assertNull(manifest)
    }

    @Test
    fun `Given an entry past the byte bound, Then readJson refuses it without reading the corrupt tail`() {
        // Given: the fixture's bytes past the bound raise on read, which is what tells a reader that
        // stops from one that reads everything and then checks a size.
        val storageKey = writeArchiveWithCorruptTail("imports/oversize.zip", "manifest.json", paddedJson())

        // When / Then
        store.open(storageKey).use { source ->
            assertThrows(ArchiveBoundExceededException::class.java) {
                source.readJson("manifest.json", ManifestFixture::class.java, BOUND_BYTES)
            }
        }
    }

    @Test
    fun `Given JSON lines, Then readJsonLines yields each of them parsed and numbered`() {
        // Given
        val first = """{"name":"first","count":1}"""
        val second = """{"name":"second","count":2}"""
        val storageKey = promoteArchive("imports/lines.zip") { writeEntry(it, "pins.jsonl", "$first\n$second") }

        // When
        val lines = readLines(storageKey)

        // Then: the last line carries no trailing newline, and is read all the same
        assertEquals(listOf(1, 2), lines.map { it.line })
        assertEquals(listOf("first", "second"), lines.map { it.value?.name })
    }

    @Test
    fun `Given a malformed line, Then it carries a failure and the walk continues`() {
        // Given
        val storageKey =
            promoteArchive("imports/malformed.zip") { zip ->
                writeEntry(zip, "pins.jsonl", "{not json\n" + """{"name":"good","count":1}""" + "\n")
            }

        // When
        val lines = readLines(storageKey)

        // Then
        assertNull(lines[0].value)
        assertTrue(lines[0].failure?.isNotBlank() == true)
        assertEquals("good", lines[1].value?.name)
    }

    @Test
    fun `Given a line whose non-nullable field is null, Then it carries a failure rather than a null field`() {
        // Given: this is what the Kotlin module buys, and why it is registered on the reader
        val storageKey =
            promoteArchive("imports/null-field.zip") { zip ->
                writeEntry(zip, "pins.jsonl", """{"name":null,"count":1}""" + "\n")
            }

        // When
        val lines = readLines(storageKey)

        // Then
        assertNull(lines.single().value)
        assertTrue(lines.single().failure?.isNotBlank() == true)
    }

    @Test
    fun `Given an absent JSON lines entry, Then the walk runs once over nothing`() {
        // Given
        val storageKey = promoteArchive("imports/no-lines.zip") { writeEntry(it, "manifest.json", "{}") }

        // When
        val lines = readLines(storageKey)

        // Then
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `Given a line past the byte bound, Then it carries a failure and the corrupt tail is never read`() {
        // Given: the same discriminator as readJson's bound, and the only one available here
        val storageKey = writeArchiveWithCorruptTail("imports/long-line.zip", "pins.jsonl", paddedJson())

        // When
        val lines = readLines(storageKey)

        // Then
        assertNull(lines.single().value)
        assertTrue(lines.single().failure?.contains("$MAX_LINE_BYTES") == true)
    }

    @Test
    fun `Given the corrupt-tail fixture, Then reading its entry whole raises`() {
        // Given: without this the two bound tests above would pass against a fixture that is merely
        // large, and would prove nothing about where the reader stopped.
        val storageKey = writeArchiveWithCorruptTail("imports/proof.zip", "manifest.json", paddedJson())

        // When / Then
        store.open(storageKey).use { source ->
            assertThrows(IOException::class.java) { checkNotNull(source.openEntry("manifest.json")).readBytes() }
        }
    }

    @Test
    fun `Given a binary entry, Then openEntry loans its raw bytes and null for an absent one`() {
        // Given
        val storageKey =
            promoteArchive("imports/binary.zip") { zip ->
                zip.putNextEntry(ZipEntry("images/a.bin"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }

        // When / Then
        store.open(storageKey).use { source ->
            assertArrayEquals(byteArrayOf(1, 2, 3), checkNotNull(source.openEntry("images/a.bin")).readBytes())
            assertNull(source.openEntry("images/absent.bin"))
        }
    }

    // --- the two adapters agree on framing and mapper configuration ---

    @Test
    fun `Given an archive written by the export sink, Then this source reads it back`() {
        // Given
        val exportStore = FilesystemZipExportArchiveStore(tempDir.toString())
        val staged =
            exportStore.stage { sink ->
                sink.putJsonEntry("manifest.json", mapOf("formatVersion" to 1, "generator" to "pinry-reborn"))
                sink.putJsonLinesEntry("pins.jsonl", sequenceOf(mapOf("name" to "first", "count" to 1)))
                sink.putBinaryEntry("images/a.bin", ByteArrayInputStream(byteArrayOf(7, 8)))
            }
        val storageKey = "imports/round-trip.zip"
        exportStore.promote(staged, storageKey)

        // When
        store.open(storageKey).use { source ->
            val manifest = source.readJson("manifest.json", ManifestFixture::class.java, 1_000)
            val lines = mutableListOf<ArchiveLine<LineFixture>>()
            source.readJsonLines("pins.jsonl", LineFixture::class.java) { lines.addAll(it) }

            // Then
            assertEquals(ManifestFixture(formatVersion = 1, generator = "pinry-reborn"), manifest)
            assertEquals(LineFixture(name = "first", count = 1), lines.single().value)
            assertArrayEquals(byteArrayOf(7, 8), checkNotNull(source.openEntry("images/a.bin")).readBytes())
        }
    }

    // --- fixtures ---

    private fun readLines(storageKey: String): List<ArchiveLine<LineFixture>> {
        val lines = mutableListOf<ArchiveLine<LineFixture>>()
        store.open(storageKey).use { source: ArchiveSource ->
            source.readJsonLines("pins.jsonl", LineFixture::class.java) { lines.addAll(it) }
        }
        return lines
    }

    /** Writes a ZIP straight under its storage key, skipping the upload path the reads do not exercise. */
    private fun promoteArchive(storageKey: String, write: (ZipOutputStream) -> Unit): String {
        val path = tempDir.resolve(storageKey)
        Files.createDirectories(path.parent)
        ZipOutputStream(Files.newOutputStream(path)).use(write)
        return storageKey
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray())
        zip.closeEntry()
    }

    /** One JSON document, compressible and far longer than any bound under test. */
    private fun paddedJson(): ByteArray =
        ("""{"name":"""" + "a".repeat(PADDING_CHARACTERS) + """","count":1}""").toByteArray()

    /**
     * An entry that decodes for its first [KEPT_COMPRESSED_BYTES] compressed bytes and then raises, so
     * reaching the corruption is only possible by reading past the bound under test.
     */
    private fun writeArchiveWithCorruptTail(storageKey: String, entryName: String, content: ByteArray): String {
        promoteArchive(storageKey) { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(content)
            zip.closeEntry()
        }
        val path = tempDir.resolve(storageKey)
        val compressedSize = ZipFile(path.toFile()).use { it.getEntry(entryName).compressedSize }
        val bytes = Files.readAllBytes(path)
        val nameLength = (bytes[26].toInt() and BYTE_MASK) or ((bytes[27].toInt() and BYTE_MASK) shl BYTE_BITS)
        val extraLength = (bytes[28].toInt() and BYTE_MASK) or ((bytes[29].toInt() and BYTE_MASK) shl BYTE_BITS)
        val dataStart = LOCAL_HEADER_BYTES + nameLength + extraLength
        // A reserved deflate block type, so the inflater cannot decode whatever the bits happen to be.
        for (index in dataStart + KEPT_COMPRESSED_BYTES until dataStart + compressedSize.toInt()) {
            bytes[index] = RESERVED_DEFLATE_BLOCK
        }
        Files.write(path, bytes)
        return storageKey
    }

    private data class ManifestFixture(val formatVersion: Int, val generator: String)

    private data class LineFixture(val name: String, val count: Int)

    private companion object {
        const val MAX_LINE_BYTES = 1_024
        const val BOUND_BYTES = 1_024L
        const val MANY_ENTRIES = 100
        const val PADDING_CHARACTERS = 2_000_000
        const val LOCAL_HEADER_BYTES = 30
        const val KEPT_COMPRESSED_BYTES = 200
        const val RESERVED_DEFLATE_BLOCK: Byte = 0x06
        const val BYTE_MASK = 0xff
        const val BYTE_BITS = 8
    }
}
