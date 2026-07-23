package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.http

/** Inclusive byte range, both bounds 0-based. */
data class ByteRange(val start: Long, val endInclusive: Long)

/**
 * Hand-rolled `Range: bytes=...` parser (spec `docs/specs/2026-07-22-user-data-export.md` §7),
 * because Quarkus REST's `Path`/`PathPart`/`FilePart` return types document no automatic `Range`
 * handling and would need a filesystem path in the controller anyway, defeating the storage port.
 *
 * Only a single `bytes=start-` or `bytes=start-end` is honoured. Everything else -- a missing
 * header, a malformed one, a multi-range (`bytes=0-10,20-30`) and a suffix range (`bytes=-500`,
 * legal HTTP but DELIBERATELY unsupported here) -- serves the full body (`null`). A naive
 * `split("-")` would parse a suffix range by accident (`""` before the dash treated as "start
 * unset" -> 0), so that case gets its own pinning test.
 */
object RangeHeader {
    private val RANGE_REGEX = Regex("""bytes=(\d+)-(\d*)""")

    /**
     * @return `null` to serve the whole body, or the honoured [ByteRange].
     * @throws RangeNotSatisfiableException if the requested start lies at or past [totalSize].
     */
    fun parse(header: String?, totalSize: Long): ByteRange? {
        val match = header?.let { RANGE_REGEX.matchEntire(it) }
        return match?.let { toByteRange(it, totalSize) }
    }

    private fun toByteRange(match: MatchResult, totalSize: Long): ByteRange {
        val start = match.groupValues[1].toLong()
        if (start >= totalSize) throw RangeNotSatisfiableException(totalSize)
        val endText = match.groupValues[2]
        val end = if (endText.isEmpty()) totalSize - 1 else endText.toLong().coerceAtMost(totalSize - 1)
        return ByteRange(start, end)
    }
}
