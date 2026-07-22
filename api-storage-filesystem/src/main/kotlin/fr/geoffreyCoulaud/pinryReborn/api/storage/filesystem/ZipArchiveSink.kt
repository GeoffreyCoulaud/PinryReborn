package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveEntryDigest
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveSink
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [ArchiveSink] backed by a [ZipOutputStream].
 *
 * Every `put*Entry` method returns the digest of the UNCOMPRESSED bytes it wrote: the per-entry
 * [CountingDigestOutputStream] sits between the caller and [zip], so it counts and digests before
 * deflation happens downstream, not after.
 */
internal class ZipArchiveSink(private val zip: ZipOutputStream, private val mapper: ObjectMapper) : ArchiveSink {

    override fun putTextEntry(name: String, text: String): ArchiveEntryDigest =
        entry(name) { out -> out.write(text.toByteArray()) }

    override fun putJsonEntry(name: String, value: Any): ArchiveEntryDigest =
        // writeValueAsBytes, never writeValue(OutputStream, ...): the latter closes its target
        // (AUTO_CLOSE_TARGET), which would close the whole ZipOutputStream after this one entry.
        entry(name) { out -> out.write(mapper.writeValueAsBytes(value)) }

    override fun putJsonLinesEntry(name: String, values: Sequence<Any>): ArchiveEntryDigest =
        entry(name) { out ->
            for (value in values) {
                out.write(mapper.writeValueAsBytes(value))
                out.write('\n'.code)
            }
        }

    override fun putBinaryEntry(name: String, bytes: InputStream): ArchiveEntryDigest {
        // setLevel applies to entries opened AFTER this call, hence the finally restoring the
        // default level once this entry is closed: leaving it at NO_COMPRESSION would silently
        // stop compressing every later entry too.
        zip.setLevel(Deflater.NO_COMPRESSION)
        try {
            return entry(name) { out -> bytes.use { it.copyTo(out) } }
        } finally {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        }
    }

    private fun entry(name: String, write: (OutputStream) -> Unit): ArchiveEntryDigest {
        zip.putNextEntry(ZipEntry(name))
        val counting = CountingDigestOutputStream(zip)
        write(counting)
        counting.flush()
        zip.closeEntry()
        return ArchiveEntryDigest(name, counting.count, counting.digestHex())
    }
}
