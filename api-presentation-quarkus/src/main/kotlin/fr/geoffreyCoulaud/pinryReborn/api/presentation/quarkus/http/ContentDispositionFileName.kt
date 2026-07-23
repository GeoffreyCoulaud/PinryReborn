package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.http

/**
 * Builds a `Content-Disposition: attachment` header value carrying both an ASCII-sanitized
 * `filename` and an RFC 6266 / RFC 5987 `filename*=UTF-8''<percent-encoded>` form.
 *
 * This is a security requirement, not cosmetics: usernames are only `trim()`ed at registration
 * (spec `docs/specs/2026-07-22-user-data-export.md` §7), so they must be treated as hostile
 * (quotes, CRLF header injection, `../` path traversal, RTL overrides, unbounded length).
 * Percent-encoding is done BY HAND: `java.net.URLEncoder` emits `+` for space and leaves `*` and
 * `'` unescaped, both of which RFC 5987 forbids in the `ext-value` production.
 */
object ContentDispositionFileName {
    private const val MAX_LENGTH = 100
    private val UNSAFE = Regex("[^A-Za-z0-9._-]+")
    private val ATTR_CHAR = Regex("[A-Za-z0-9!#$&+^_`{}~.-]")

    fun headerValue(rawName: String, fallback: String): String {
        val ascii = UNSAFE.replace(rawName, "-").trim('-').take(MAX_LENGTH).ifEmpty { fallback }
        val encoded = rawName.take(MAX_LENGTH).toByteArray().joinToString("") { byte ->
            val ch = byte.toInt().toChar()
            if (ATTR_CHAR.matches(ch.toString())) ch.toString() else "%%%02X".format(byte)
        }
        return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }
}
