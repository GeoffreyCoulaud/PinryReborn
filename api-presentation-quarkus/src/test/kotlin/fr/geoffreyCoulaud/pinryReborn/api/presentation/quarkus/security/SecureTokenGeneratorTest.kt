package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecureTokenGeneratorTest {
    private val generator = SecureTokenGenerator()

    @Test
    fun `Given generateToken, Then it returns a non-blank URL-safe token`() {
        val token = generator.generateToken()
        assertTrue(token.isNotBlank())
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "URL-safe base64url charset")
    }

    @Test
    fun `Given two calls, Then the tokens differ`() {
        assertNotEquals(generator.generateToken(), generator.generateToken())
    }
}
