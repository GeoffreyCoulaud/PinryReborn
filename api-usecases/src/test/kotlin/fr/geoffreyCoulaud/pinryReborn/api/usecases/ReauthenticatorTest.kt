package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ReauthenticationError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.util.UUID.randomUUID

class ReauthenticatorTest : BaseTest() {
    private val passwords = mockk<UserPasswordHashRepositoryInterface>()
    private val hasher = mockk<PasswordHasher>()
    private val reauth = Reauthenticator(passwords, hasher)
    private val user = User(id = randomUUID(), name = "u", createdAt = Instant.now())
    private val hash = HashedPassword("h", PasswordHashAlgorithm.BCRYPT, createdAt = Instant.now())

    @Test
    fun `Given the correct factor, Then it passes`() {
        every { passwords.findCurrentPasswordHash(user) } returns hash
        every { hasher.matches("secret", hash) } returns true
        assertDoesNotThrow { reauth.reauthenticate(user, "secret") }
    }

    @Test
    fun `Given a wrong factor, Then it throws`() {
        every { passwords.findCurrentPasswordHash(user) } returns hash
        every { hasher.matches("bad", hash) } returns false
        assertThrows<ReauthenticationError> { reauth.reauthenticate(user, "bad") }
    }

    @Test
    fun `Given no stored hash, Then it throws`() {
        every { passwords.findCurrentPasswordHash(user) } returns null
        assertThrows<ReauthenticationError> { reauth.reauthenticate(user, "x") }
    }
}
