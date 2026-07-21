package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.IssuedSession
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Login.BasicAuthLogin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.SessionExpiryPolicy
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.TokenGenerator
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UserAuthenticationInvalidPasswordError
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID.randomUUID

class SessionCreatorTest {
    private val userAuthenticator = mockk<UserAuthenticator>()
    private val repository = mockk<SessionTokenRepositoryInterface>(relaxed = true)
    private val tokenGenerator = mockk<TokenGenerator>()
    private val clock = mockk<Clock>()
    private val policy = SessionExpiryPolicy(Duration.ofDays(30), Duration.ofHours(12), 0.75)
    private val creator = SessionCreator(userAuthenticator, repository, tokenGenerator, clock, policy)

    private val now = Instant.parse("2026-07-21T00:00:00Z")
    private val user = User(id = randomUUID(), name = "alice")

    @Test
    fun `Given valid credentials, Then create issues, stores the hash, and returns the token with expiry metadata`() {
        every { userAuthenticator.authenticate(any()) } returns user
        every { tokenGenerator.generateToken() } returns "plain-token"
        every { clock.now() } returns now

        val issued = creator.create(name = "alice", password = "pw", persistent = true)

        val expectedExpiry = now.plus(Duration.ofDays(30))
        assertEquals(IssuedSession("plain-token", expectedExpiry, policy.renewAfterFor(expectedExpiry, true)), issued)

        val saved = slot<SessionToken>()
        verify { repository.saveSessionToken(capture(saved), tokenHash = TokenHasher.sha256("plain-token")) }
        assertEquals(user, saved.captured.user)
        assertEquals(expectedExpiry, saved.captured.expiresAt)
        assertEquals(true, saved.captured.persistent)
    }

    @Test
    fun `Given valid credentials, Then create authenticates via a BasicAuthLogin carrying them`() {
        every { tokenGenerator.generateToken() } returns "t"
        every { clock.now() } returns now
        val login = slot<BasicAuthLogin>()
        every { userAuthenticator.authenticate(capture(login)) } returns user

        creator.create(name = "alice", password = "pw", persistent = false)

        assertEquals("alice", login.captured.userName)
        assertEquals("pw", login.captured.password)
    }

    @Test
    fun `Given invalid credentials, Then create propagates the authentication error and stores nothing`() {
        every { userAuthenticator.authenticate(any()) } throws UserAuthenticationInvalidPasswordError()
        assertThrows<UserAuthenticationInvalidPasswordError> { creator.create("alice", "bad", persistent = false) }
        verify(exactly = 0) { repository.saveSessionToken(any(), any()) }
    }
}
