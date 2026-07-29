package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.IssuedSession
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.SessionExpiryPolicy
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input.SessionCreationInputDto
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SessionCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SessionRenewer
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SessionRevoker
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UserAuthenticationInvalidPasswordError
import io.quarkus.security.AuthenticationFailedException
import io.quarkus.security.identity.SecurityIdentity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID.randomUUID

class SessionControllerTest {
    private val creator = mockk<SessionCreator>()
    private val renewer = mockk<SessionRenewer>(relaxed = true)
    private val revoker = mockk<SessionRevoker>(relaxed = true)
    private val policy = SessionExpiryPolicy(Duration.ofDays(30), Duration.ofHours(12), 0.75)
    private val identity = mockk<SecurityIdentity>()
    private val controller = SessionController(creator, renewer, revoker, policy, identity)

    private val user = User(randomUUID(), "alice", createdAt = Instant.now())
    private val issued = IssuedSession(
        "tok",
        Instant.parse("2026-08-01T00:00:00Z"),
        Instant.parse("2026-07-25T00:00:00Z"),
    )

    @Test
    fun `Given rememberMe true, Then createSession passes persistent=true and returns the created dto`() {
        every { creator.create(name = "alice", password = "pw", persistent = true) } returns issued
        val response = controller.createSession(SessionCreationInputDto("alice", "pw", rememberMe = true))
        assertEquals("tok", response.entity!!.token)
        assertEquals("no-store", response.getHeaderString("Cache-Control"))
        verify { creator.create(name = "alice", password = "pw", persistent = true) }
    }

    @Test
    fun `Given rememberMe absent (null), Then createSession defaults persistent to false`() {
        every { creator.create(name = "alice", password = "pw", persistent = false) } returns issued
        controller.createSession(SessionCreationInputDto("alice", "pw", rememberMe = null))
        verify { creator.create(name = "alice", password = "pw", persistent = false) }
    }

    @Test
    fun `Given invalid credentials, Then createSession raises AuthenticationFailedException`() {
        every { creator.create(any(), any(), any()) } throws UserAuthenticationInvalidPasswordError()
        assertThrows<AuthenticationFailedException> {
            controller.createSession(SessionCreationInputDto("alice", "bad", rememberMe = null))
        }
    }

    @Test
    fun `Given a current session, Then getCurrentSession returns its metadata without a token`() {
        val current = SessionToken(
            randomUUID(),
            user,
            Instant.parse("2026-08-01T00:00:00Z"),
            persistent = true,
            createdAt = Instant.now(),
        )
        every { identity.getAttribute<SessionToken>("sessionToken") } returns current
        val dto = controller.getCurrentSession()
        assertEquals(current.expiresAt, dto.expiresAt)
        assertEquals(policy.renewAfterFor(current.expiresAt, true), dto.renewAfter)
        assertEquals(true, dto.persistent)
    }

    @Test
    fun `Given a current session, Then renewSession delegates to the renewer and returns the new token`() {
        val current = SessionToken(randomUUID(), user, Instant.now(), persistent = false, createdAt = Instant.now())
        every { identity.getAttribute<SessionToken>("sessionToken") } returns current
        every { renewer.renew(current) } returns issued
        val response = controller.renewSession()
        assertEquals("tok", response.entity!!.token)
        assertEquals("no-store", response.getHeaderString("Cache-Control"))
    }

    @Test
    fun `Given a current session, Then revokeCurrentSession deletes the current token`() {
        val current = SessionToken(randomUUID(), user, Instant.now(), persistent = false, createdAt = Instant.now())
        every { identity.getAttribute<SessionToken>("sessionToken") } returns current
        controller.revokeCurrentSession()
        verify { revoker.revokeCurrent(current) }
    }

    @Test
    fun `Given the caller, Then revokeAllSessions deletes all their tokens`() {
        every { identity.getAttribute<User>("user") } returns user
        controller.revokeAllSessions()
        verify { revoker.revokeAll(user) }
    }
}
