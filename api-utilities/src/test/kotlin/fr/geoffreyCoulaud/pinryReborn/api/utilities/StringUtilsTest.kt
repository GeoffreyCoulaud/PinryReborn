package fr.geoffreyCoulaud.pinryReborn.api.utilities

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StringUtilsTest {
    @Test
    fun `Given no arguments, Then uses the default length`() {
        // When
        val result = createRandomString()

        // Then
        assertEquals(32, result.length)
    }

    @Test
    fun `Given an explicit length and alphabet, Then honours them`() {
        // Given
        val alphabet = "ab"

        // When
        val result = createRandomString(length = 8, alphabet = alphabet)

        // Then
        assertEquals(8, result.length)
        assertTrue(result.all { it in alphabet })
    }
}
