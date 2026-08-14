package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveBoundExceededException
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveLine
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveSource
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * [ArchiveSource] over a [ZipFile], reading random entries out of hostile input. Every read refuses
 * its bound by stopping at it, never by reading to the end and measuring afterwards.
 */
internal class ZipArchiveSource(
    private val zip: ZipFile,
    private val mapper: ObjectMapper,
    private val maxLineBytes: Int,
) : ArchiveSource {
    override fun entryNames(maxEntries: Int): Set<String> {
        // The central directory is already read by the time the ZIP opened, so this bound refuses an
        // archive rather than stopping a read early. Nothing more is claimed for it.
        val declared = zip.size()
        if (declared > maxEntries) {
            throw ArchiveBoundExceededException("Archive declares $declared entries, past the $maxEntries allowed")
        }
        return zip.entries().asSequence().map { it.name }.toSet()
    }

    override fun <T : Any> readJson(
        name: String,
        type: Class<T>,
        maxBytes: Long,
    ): T? {
        val entry = zip.getEntry(name) ?: return null
        val limit = maxBytes.coerceAtMost(MAX_READ_BYTES).toInt()
        val bytes = zip.getInputStream(entry).use { it.readNBytes(limit + 1) }
        if (bytes.size > limit) {
            throw ArchiveBoundExceededException("Entry $name is larger than the $maxBytes bytes allowed")
        }
        return mapper.readValue(bytes, type)
    }

    override fun <T : Any> readJsonLines(
        name: String,
        type: Class<T>,
        block: (Sequence<ArchiveLine<T>>) -> Unit,
    ) {
        val entry = zip.getEntry(name)
        if (entry == null) {
            block(emptySequence())
            return
        }
        // Buffered, so the per-byte scan below does not cross the inflater once per byte.
        BufferedInputStream(zip.getInputStream(entry)).use { stream -> block(lines(stream, type)) }
    }

    override fun openEntry(name: String): InputStream? = zip.getEntry(name)?.let { zip.getInputStream(it) }

    override fun close() = zip.close()

    /**
     * A bad line is reported and walked past, never ending the walk: a walk that ends is
     * indistinguishable from the end of the entry, so every later line would be dropped unreported.
     */
    private fun <T : Any> lines(
        stream: InputStream,
        type: Class<T>,
    ): Sequence<ArchiveLine<T>> =
        sequence {
            var number = 0
            while (true) {
                val line = readLine(stream) ?: return@sequence
                number++
                val read = read(number, line, type)
                if (read != null) yield(read)
            }
        }

    /** Null for a line of no bytes: it holds no entry, so it is not an entry that failed. */
    private fun <T : Any> read(
        number: Int,
        line: ReadLine,
        type: Class<T>,
    ): ArchiveLine<T>? =
        when {
            line.overLong -> ZipArchiveLine(number, null, "Line is longer than the $maxLineBytes bytes allowed")
            line.bytes.isEmpty() -> null
            else -> parse(number, line.bytes, type)
        }

    private fun <T : Any> parse(
        number: Int,
        bytes: ByteArray,
        type: Class<T>,
    ): ArchiveLine<T> =
        try {
            ZipArchiveLine(number, mapper.readValue(bytes, type), null)
        } catch (error: JacksonException) {
            ZipArchiveLine(number, null, error.message)
        }

    /** The next line, or null at the end of the entry; the bound is exclusive and the last newline optional. */
    private fun readLine(stream: InputStream): ReadLine? {
        val buffer = ByteArrayOutputStream()
        while (buffer.size() < maxLineBytes) {
            val next = stream.read()
            if (next < 0 || next == NEWLINE) {
                val ended = next < 0 && buffer.size() == 0
                return if (ended) null else ReadLine(buffer.toByteArray(), overLong = false)
            }
            buffer.write(next)
        }
        skipToNextLine(stream)
        return ReadLine(ByteArray(0), overLong = true)
    }

    /** Discards the rest of an over-long line, allocating nothing, so the next line is still read. */
    private fun skipToNextLine(stream: InputStream) {
        while (true) {
            val next = stream.read()
            if (next < 0 || next == NEWLINE) return
        }
    }

    private class ReadLine(val bytes: ByteArray, val overLong: Boolean)

    private data class ZipArchiveLine<out T>(
        override val line: Int,
        override val value: T?,
        override val failure: String?,
    ) : ArchiveLine<T>

    private companion object {
        const val NEWLINE = '\n'.code

        // readNBytes takes an Int, and the JDK caps an array below Int.MAX_VALUE anyway.
        const val MAX_READ_BYTES = Int.MAX_VALUE.toLong() - 8
    }
}
