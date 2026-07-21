package fr.geoffreyCoulaud.pinryReborn.api.usecases

import java.security.MessageDigest

/** Hashes an opaque session token for storage/lookup. SHA-256 is enough: the input is already
 *  256 bits of entropy, so no salted/slow KDF is needed on the per-request hot path. */
object TokenHasher {
    fun sha256(token: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
