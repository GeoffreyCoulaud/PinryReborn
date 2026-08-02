package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StorageCleanupTest : BaseTest() {

    @Test
    fun `Given a succeeding block, Then runQuietly runs it`() {
        var calls = 0

        // Given
        val block: () -> Unit = { calls += 1 }

        // When
        StorageCleanup.runQuietly("image key", block)

        // Then
        assertEquals(1, calls)
    }

    @Test
    fun `Given a throwing block, Then runQuietly does not propagate`() {
        var attempted = false

        // Given
        val block: () -> Unit = {
            attempted = true
            error("disk on fire")
        }

        // When
        assertDoesNotThrow { StorageCleanup.runQuietly("image key", block) }

        // Then
        assertTrue(attempted)
    }
}
