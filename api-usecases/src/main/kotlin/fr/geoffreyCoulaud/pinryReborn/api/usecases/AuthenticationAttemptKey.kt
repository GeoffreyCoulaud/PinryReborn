package fr.geoffreyCoulaud.pinryReborn.api.usecases

import java.util.Locale
import java.util.UUID

/** The key spaces of [AuthenticationAttemptKey], kept apart so a name can never share a user's counter. */
enum class AuthenticationAttemptKeySpace {
    LOGIN,
    USER,
}

/**
 * One failure counter's identity. The constructor and [copy] are closed: the factories below own
 * the normalisation (`docs/specs/2026-08-13-auth-attempt-limiting.md`, D3, D4 and D9).
 */
@ConsistentCopyVisibility
data class AuthenticationAttemptKey private constructor(
    val space: AuthenticationAttemptKeySpace,
    val value: String,
) {
    companion object {
        /** The submitted name, counted whether or not that user exists. Lower-cased with [Locale.ROOT],
         *  since the store matches names case-insensitively, then digested, since undigested one entry
         *  weighs what the caller sent (`docs/adr/0013-in-memory-authentication-attempt-limiting.md`,
         *  decision 4). Lower-cased first, or the folding would depend on the name's length. */
        fun forLogin(name: String) =
            AuthenticationAttemptKey(
                AuthenticationAttemptKeySpace.LOGIN,
                TokenHasher.sha256(name.lowercase(Locale.ROOT)),
            )

        /** Re-authentication and password change share this counter: it is the same secret. */
        fun forUser(userId: UUID) = AuthenticationAttemptKey(AuthenticationAttemptKeySpace.USER, userId.toString())
    }
}
