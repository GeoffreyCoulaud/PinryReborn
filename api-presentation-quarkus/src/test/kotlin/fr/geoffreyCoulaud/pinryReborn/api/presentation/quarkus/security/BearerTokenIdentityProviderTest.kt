package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SessionTokenAuthenticator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenExpiredError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenInvalidError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import io.quarkus.security.AuthenticationFailedException
import io.quarkus.security.credential.TokenCredential
import io.quarkus.security.identity.AuthenticationRequestContext
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.request.TokenAuthenticationRequest
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID.randomUUID
import java.util.function.Supplier

class BearerTokenIdentityProviderTest {
    private val authenticator = mockk<SessionTokenAuthenticator>()
    private val provider = BearerTokenIdentityProvider(authenticator)
    private val user = User(randomUUID(), "alice", createdAt = TestTime.now)

    // Execute the runBlocking supplier synchronously.
    private val context = mockk<AuthenticationRequestContext> {
        every { runBlocking(any<Supplier<SecurityIdentity>>()) } answers {
            Uni.createFrom().item(firstArg<Supplier<SecurityIdentity>>().get())
        }
    }

    private fun request(token: String) = TokenAuthenticationRequest(TokenCredential(token, "bearer"))

    @Test
    fun `Given a valid token, Then the identity carries the user, userId and sessionToken`() {
        val session = SessionToken(
            randomUUID(),
            user,
            TestTime.now.plusSeconds(60),
            persistent = true,
            createdAt = TestTime.now,
        )
        every { authenticator.authenticate("good") } returns session

        val identity = provider.authenticate(request("good"), context).await().indefinitely()

        assertEquals("alice", identity.principal.name)
        assertEquals(user.id, identity.getAttribute("userId"))
        assertEquals(user, identity.getAttribute<User>("user"))
        assertEquals(session, identity.getAttribute<SessionToken>("sessionToken"))
    }

    @Test
    fun `Given an invalid token, Then it throws AuthenticationFailedException with a SessionTokenInvalidError cause`() {
        val invalidError = SessionTokenInvalidError()
        every { authenticator.authenticate("bad") } throws invalidError

        val exception = assertThrows<AuthenticationFailedException> {
            provider.authenticate(request("bad"), context).await().indefinitely()
        }

        assertEquals(invalidError, exception.cause)
    }

    @Test
    fun `Given an expired token, Then it throws AuthenticationFailedException with a SessionTokenExpiredError cause`() {
        // Spec §12: a dedicated exception subtype could not be routed through a JAX-RS mapper at
        // runtime (verified in Task 9), so both branches throw the same (proven to route)
        // AuthenticationFailedException; AuthenticationFailedExceptionMapper distinguishes SESSION_EXPIRED
        // from AUTHENTICATION_FAILED by inspecting this cause.
        val expiredError = SessionTokenExpiredError()
        every { authenticator.authenticate("old") } throws expiredError

        val exception = assertThrows<AuthenticationFailedException> {
            provider.authenticate(request("old"), context).await().indefinitely()
        }

        assertEquals(expiredError, exception.cause)
    }
}
