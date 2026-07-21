package fr.geoffreyCoulaud.pinryReborn.api.domain.security

import java.time.Duration
import java.time.Instant

/**
 * Pure expiry policy. `expiryFrom` is `now + ttl`; `renewAfterFor` is the recommended soft-renewal
 * instant, `expiresAt - ttl * (1 - renewThreshold)` (renew once renewThreshold of the lifetime has
 * elapsed). Defining renewAfter from expiresAt lets it be recomputed for an already-stored token.
 */
class SessionExpiryPolicy(
    private val persistentTtl: Duration,
    private val ephemeralTtl: Duration,
    private val renewThreshold: Double,
) {
    private fun ttl(persistent: Boolean): Duration = if (persistent) persistentTtl else ephemeralTtl

    fun expiryFrom(now: Instant, persistent: Boolean): Instant = now.plus(ttl(persistent))

    fun renewAfterFor(expiresAt: Instant, persistent: Boolean): Instant {
        val ttlMillis = ttl(persistent).toMillis()
        val remainingBeforeRenewMillis = (ttlMillis * (1.0 - renewThreshold)).toLong()
        return expiresAt.minusMillis(remainingBeforeRenewMillis)
    }
}
