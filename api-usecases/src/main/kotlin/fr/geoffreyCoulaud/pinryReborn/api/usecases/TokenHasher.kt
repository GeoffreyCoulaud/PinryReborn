package fr.geoffreyCoulaud.pinryReborn.api.usecases

import java.security.MessageDigest
import java.util.Locale

/** SHA-256, as lowercase hexadecimal. Enough for both its callers: an opaque session token, stored
 *  and looked up by its digest, is already 256 bits of entropy, so no salted or slow derivation is
 *  needed on the per-request hot path; an authentication attempt key is only grouped by its digest,
 *  never trusted as a secret, and wants the fixed width. */
object TokenHasher {
    fun sha256(token: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte) }
}
