package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenExpiredError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenInvalidError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID.randomUUID

class SessionTokenAuthenticatorTest {
    private val repository = mockk<SessionTokenRepositoryInterface>()
    private val clock = mockk<Clock>()
    private val authenticator = SessionTokenAuthenticator(repository, clock)

    private val now = Instant.parse("2026-07-21T00:00:00Z")
    private val user = User(id = randomUUID(), name = "alice", createdAt = TestTime.now)
    private val plaintext = "the-token"
    private val hash = TokenHasher.sha256(plaintext)

    @Test
    fun `Given a valid unexpired token, Then authenticate returns its session token`() {
        val token = SessionToken(
            randomUUID(),
            user,
            expiresAt = now.plusSeconds(60),
            persistent = false,
            createdAt = now,
        )
        every { clock.now() } returns now
        every { repository.findByTokenHash(hash) } returns token

        assertEquals(token, authenticator.authenticate(plaintext))
    }

    @Test
    fun `Given no token for the hash, Then authenticate throws SessionTokenInvalidError`() {
        every { repository.findByTokenHash(hash) } returns null
        assertThrows<SessionTokenInvalidError> { authenticator.authenticate(plaintext) }
    }

    @Test
    fun `Given an expired token, Then authenticate throws SessionTokenExpiredError`() {
        val token = SessionToken(
            randomUUID(),
            user,
            expiresAt = now.minusSeconds(1),
            persistent = false,
            createdAt = now,
        )
        every { clock.now() } returns now
        every { repository.findByTokenHash(hash) } returns token
        assertThrows<SessionTokenExpiredError> { authenticator.authenticate(plaintext) }
    }

    @Test
    fun `Given a token expiring exactly now, Then it is treated as expired`() {
        val token = SessionToken(
            randomUUID(),
            user,
            expiresAt = now,
            persistent = false,
            createdAt = now,
        )
        every { clock.now() } returns now
        every { repository.findByTokenHash(hash) } returns token
        assertThrows<SessionTokenExpiredError> { authenticator.authenticate(plaintext) }
    }
}
