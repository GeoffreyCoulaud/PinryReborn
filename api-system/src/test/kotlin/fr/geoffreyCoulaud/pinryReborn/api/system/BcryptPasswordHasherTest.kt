package fr.geoffreyCoulaud.pinryReborn.api.system

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class BcryptPasswordHasherTest {
    private val hasher = BcryptPasswordHasher()

    @Test
    fun `Given a raw password, Then hash then matches round-trips`() {
        // Given
        val raw = "correct horse battery staple"
        // When
        val hashed = hasher.hash(raw, Instant.now())
        // Then
        assertEquals(PasswordHashAlgorithm.BCRYPT, hashed.algorithm)
        assertTrue(hasher.matches(raw, hashed))
    }

    @Test
    fun `Given a wrong password, Then matches is false`() {
        // Given
        val hashed = hasher.hash("right", Instant.now())
        // When / Then
        assertFalse(hasher.matches("wrong", hashed))
    }
}
