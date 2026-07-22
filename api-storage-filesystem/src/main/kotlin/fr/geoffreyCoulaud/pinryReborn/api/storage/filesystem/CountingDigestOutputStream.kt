package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Counts and digests bytes on the way through, WITHOUT closing the delegate.
 *
 * Used both to measure a whole staged archive file and, per entry, the uncompressed bytes written
 * into a ZIP entry: in the latter case the delegate is the shared [java.util.zip.ZipOutputStream],
 * which must stay open across entries.
 */
internal class CountingDigestOutputStream(delegate: OutputStream) : FilterOutputStream(delegate) {
    private val digest = MessageDigest.getInstance("SHA-256")
    var count: Long = 0
        private set

    override fun write(b: Int) {
        out.write(b)
        digest.update(b.toByte())
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        digest.update(b, off, len)
        count += len
    }

    /** Flushes only: a ZIP entry stream (or the file it wraps) must outlive this wrapper. */
    override fun close() = flush()

    fun digestHex(): String = HEX.formatHex(digest.digest())

    private companion object {
        private val HEX = HexFormat.of()
    }
}
