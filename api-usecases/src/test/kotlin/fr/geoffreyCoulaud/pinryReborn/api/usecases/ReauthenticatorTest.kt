package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ReauthenticationError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.TooManyAuthenticationAttemptsError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.UUID.randomUUID

class ReauthenticatorTest : BaseTest() {
    private val passwords = mockk<UserPasswordHashRepositoryInterface>()
    private val hasher = mockk<PasswordHasher>()
    private val limiterClock = mockk<Clock>()
    private val threshold = 3

    // Built lazily, from the test body: BaseTest clears every stub after the instance is constructed.
    private val limiter by lazy {
        every { limiterClock.now() } returns TestTime.now
        AuthenticationAttemptLimiter(
            clock = limiterClock,
            threshold = threshold,
            backoffSteps = listOf(Duration.ofSeconds(30)),
            forgetAfter = Duration.ofMinutes(15),
            maxTrackedKeys = 100,
        )
    }
    private val reauth by lazy { Reauthenticator(passwords, hasher, limiter) }

    private val user = User(id = randomUUID(), name = "u", createdAt = TestTime.now)
    private val hash = HashedPassword("h", PasswordHashAlgorithm.BCRYPT, createdAt = TestTime.now)

    private fun failTimes(count: Int) =
        repeat(count) { assertThrows<ReauthenticationError> { reauth.reauthenticate(user, "bad") } }

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

    @Test
    fun `Given the threshold reached on wrong factors, Then the next attempt is refused`() {
        // Given
        every { passwords.findCurrentPasswordHash(user) } returns hash
        every { hasher.matches("bad", hash) } returns false
        failTimes(threshold)

        // When
        val error = assertThrows<TooManyAuthenticationAttemptsError> { reauth.reauthenticate(user, "bad") }

        // Then
        assertEquals(30, error.retryAfterSeconds)
    }

    @Test
    fun `Given no stored hash, Then the failures still count`() {
        // Given: the refusal costs no hashing, but it is still a refused guess
        every { passwords.findCurrentPasswordHash(user) } returns null
        failTimes(threshold)

        // When, Then
        assertThrows<TooManyAuthenticationAttemptsError> { reauth.reauthenticate(user, "bad") }
    }

    @Test
    fun `Given a blocked user, Then no factor reaches the hasher`() {
        // Given
        every { passwords.findCurrentPasswordHash(user) } returns hash
        every { hasher.matches("bad", hash) } returns false
        failTimes(threshold)

        // When
        assertThrows<TooManyAuthenticationAttemptsError> { reauth.reauthenticate(user, "bad") }

        // Then: one hash per attempt up to the block, none for the refused one (spec D6)
        verify(exactly = threshold) { hasher.matches(any(), any()) }
    }

    @Test
    fun `Given a success before the threshold, Then the failures before it are dropped`() {
        // Given: one failure short of the threshold
        every { passwords.findCurrentPasswordHash(user) } returns hash
        every { hasher.matches("bad", hash) } returns false
        failTimes(threshold - 1)

        // When: the right factor, then as many failures again
        every { hasher.matches("secret", hash) } returns true
        reauth.reauthenticate(user, "secret")

        // Then: the count restarted at the success, so the threshold is out of reach
        failTimes(threshold - 1)
    }

    @Test
    fun `Given one user is blocked, Then another user is untouched`() {
        // Given
        every { passwords.findCurrentPasswordHash(any()) } returns hash
        every { hasher.matches("bad", hash) } returns false
        failTimes(threshold)

        // When, Then: the counter is keyed by the user, not shared by every user (spec D4)
        val other = User(id = randomUUID(), name = "other", createdAt = TestTime.now)
        assertThrows<ReauthenticationError> { reauth.reauthenticate(other, "bad") }
    }
}
