package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaskContextTest {
    @Test
    fun `Given a fresh context, Then its lease heartbeat is a harmless no-op`() {
        // Given
        val context = TaskContext(attempt = 1, maxAttempts = 3)

        // When / Then (the default heartbeat must be safe to call and do nothing)
        context.renewLease()
    }

    @Test
    fun `Given a custom heartbeat, Then calling renewLease runs it`() {
        // Given
        val context = TaskContext(attempt = 1, maxAttempts = 3)
        var beats = 0
        context.renewLease = { beats++ }

        // When
        context.renewLease()

        // Then
        assertEquals(1, beats)
    }

    @Test
    fun `Given two contexts with the same attempt but different heartbeats, Then they are equal`() {
        // Given
        val first = TaskContext(attempt = 2, maxAttempts = 3)
        val second = TaskContext(attempt = 2, maxAttempts = 3).apply { renewLease = { error("never") } }

        // When / Then (heartbeat is not part of the value identity)
        assertTrue(first == second)
    }
}
