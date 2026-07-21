package fr.geoffreyCoulaud.pinryReborn.api.domain.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class SessionExpiryPolicyTest {
    private val now = Instant.parse("2026-07-21T00:00:00Z")
    private val policy = SessionExpiryPolicy(
        persistentTtl = Duration.ofDays(30),
        ephemeralTtl = Duration.ofHours(12),
        renewThreshold = 0.75,
    )

    @Test
    fun `Given a persistent session, Then expiryFrom adds the persistent TTL`() {
        assertEquals(now.plus(Duration.ofDays(30)), policy.expiryFrom(now, persistent = true))
    }

    @Test
    fun `Given an ephemeral session, Then expiryFrom adds the ephemeral TTL`() {
        assertEquals(now.plus(Duration.ofHours(12)), policy.expiryFrom(now, persistent = false))
    }

    @Test
    fun `Given a persistent expiry, Then renewAfterFor is expiry minus 25 percent of the persistent TTL`() {
        val expiresAt = now.plus(Duration.ofDays(30))
        // 30d * (1 - 0.75) = 7.5d before expiry
        assertEquals(expiresAt.minus(Duration.ofHours(180)), policy.renewAfterFor(expiresAt, persistent = true))
    }

    @Test
    fun `Given an ephemeral expiry, Then renewAfterFor is expiry minus 25 percent of the ephemeral TTL`() {
        val expiresAt = now.plus(Duration.ofHours(12))
        // 12h * (1 - 0.75) = 3h before expiry
        assertEquals(expiresAt.minus(Duration.ofHours(3)), policy.renewAfterFor(expiresAt, persistent = false))
    }
}
