package fr.geoffreyCoulaud.pinryReborn.api.domain.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ExponentialBackoffWithJitterTest {
    private val base = Duration.ofSeconds(1)
    private val cap = Duration.ofSeconds(10)
    private val now = Instant.parse("2026-07-08T00:00:00Z")

    private fun policy(random: Double) =
        ExponentialBackoffWithJitter(base = base, cap = cap, random = { random })

    @Test
    fun `Given attempt 1 and full jitter, Then delay is the base window`() {
        // Given
        val backoff = policy(random = 1.0)
        // When
        val next = backoff.nextAttemptAt(attempts = 1, now = now)
        // Then: window = min(cap, base * 2^0) = 1s ; delay = 1.0 * 1s
        assertEquals(now.plusSeconds(1), next)
    }

    @Test
    fun `Given attempt 3, Then window grows exponentially before the cap`() {
        // Given
        val backoff = policy(random = 1.0)
        // When: window = base * 2^2 = 4s (< cap)
        val next = backoff.nextAttemptAt(attempts = 3, now = now)
        // Then
        assertEquals(now.plusSeconds(4), next)
    }

    @Test
    fun `Given a large attempt, Then the window is clamped to the cap`() {
        // Given
        val backoff = policy(random = 1.0)
        // When: base * 2^9 = 512s, clamped to cap = 10s
        val next = backoff.nextAttemptAt(attempts = 10, now = now)
        // Then
        assertEquals(now.plusSeconds(10), next)
    }

    @Test
    fun `Given zero random, Then delay is zero`() {
        // Given
        val backoff = policy(random = 0.0)
        // When
        val next = backoff.nextAttemptAt(attempts = 5, now = now)
        // Then
        assertEquals(now, next)
    }

    @Test
    fun `Given attempts at or below zero, Then it behaves like attempt one`() {
        // Given
        val backoff = policy(random = 1.0)
        // When
        val next = backoff.nextAttemptAt(attempts = 0, now = now)
        // Then
        assertEquals(now.plusSeconds(1), next)
    }

    @Test
    fun `Given a very large attempt, Then the shift is guarded and the cap applies`() {
        // Given
        val backoff = policy(random = 1.0)
        // When
        val next = backoff.nextAttemptAt(attempts = 40, now = now)
        // Then
        assertEquals(now.plus(cap), next)
    }
}
