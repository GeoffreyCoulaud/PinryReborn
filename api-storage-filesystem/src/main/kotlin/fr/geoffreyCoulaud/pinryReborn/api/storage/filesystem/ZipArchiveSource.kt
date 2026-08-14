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
     * An over-long line ends the walk rather than being skipped over: skipping means reading the very
     * bytes the bound exists to refuse, which is the difference the bound is there to make.
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
                if (line.overLong) {
                    yield(ZipArchiveLine<T>(number, null, "Line is longer than the $maxLineBytes bytes allowed"))
                    return@sequence
                }
                yield(parse(number, line.bytes, type))
            }
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
        return ReadLine(ByteArray(0), overLong = true)
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
