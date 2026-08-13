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
 * the normalisation (`docs/specs/2026-08-13-auth-attempt-limiting.md`, D3 and D4).
 */
@ConsistentCopyVisibility
data class AuthenticationAttemptKey private constructor(
    val space: AuthenticationAttemptKeySpace,
    val value: String,
) {
    companion object {
        /**
         * The submitted name, counted whether or not that user exists, lower-cased with [Locale.ROOT]:
         * the store matches names case-insensitively, so a case-sensitive counter would be bypassed.
         */
        fun forLogin(name: String) =
            AuthenticationAttemptKey(AuthenticationAttemptKeySpace.LOGIN, name.lowercase(Locale.ROOT))

        /** Re-authentication and password change share this counter: it is the same secret. */
        fun forUser(userId: UUID) = AuthenticationAttemptKey(AuthenticationAttemptKeySpace.USER, userId.toString())
    }
}
