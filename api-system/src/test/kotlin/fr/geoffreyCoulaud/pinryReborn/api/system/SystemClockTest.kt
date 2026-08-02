package fr.geoffreyCoulaud.pinryReborn.api.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

// SystemClock is the clock adapter under test; its test compares against the real wall clock.
@Suppress("WallClockRead")
class SystemClockTest {
    @Test
    fun `Given the system clock, Then now is truncated to millisecond precision`() {
        // Given
        val clock = SystemClock()

        // When
        val now = clock.now()

        // Then
        assertEquals(0, now.nano % 1_000_000)
    }

    @Test
    fun `Given the system clock, Then now tracks the wall clock`() {
        // Given
        val clock = SystemClock()
        val before = Instant.now()

        // When
        val now = clock.now()

        // Then
        assertTrue(Duration.between(before, now).abs() < Duration.ofMinutes(1))
    }
}
