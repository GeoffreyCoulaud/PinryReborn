package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SessionTokenAuthenticator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenExpiredError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenInvalidError
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
import java.time.Instant
import java.util.UUID.randomUUID
import java.util.function.Supplier

class BearerTokenIdentityProviderTest {
    private val authenticator = mockk<SessionTokenAuthenticator>()
    private val provider = BearerTokenIdentityProvider(authenticator)
    private val user = User(randomUUID(), "alice")

    // Execute the runBlocking supplier synchronously.
    private val context = mockk<AuthenticationRequestContext> {
        every { runBlocking(any<Supplier<SecurityIdentity>>()) } answers {
            Uni.createFrom().item(firstArg<Supplier<SecurityIdentity>>().get())
        }
    }

    private fun request(token: String) = TokenAuthenticationRequest(TokenCredential(token, "bearer"))

    @Test
    fun `Given a valid token, Then the identity carries the user, userId and sessionToken`() {
        val session = SessionToken(randomUUID(), user, Instant.now().plusSeconds(60), persistent = true)
        every { authenticator.authenticate("good") } returns session

        val identity = provider.authenticate(request("good"), context).await().indefinitely()

        assertEquals("alice", identity.principal.name)
        assertEquals(user.id, identity.getAttribute("userId"))
        assertEquals(user, identity.getAttribute<User>("user"))
        assertEquals(session, identity.getAttribute<SessionToken>("sessionToken"))
    }

    @Test
    fun `Given an invalid token, Then it throws AuthenticationFailedException`() {
        every { authenticator.authenticate("bad") } throws SessionTokenInvalidError()
        assertThrows<AuthenticationFailedException> {
            provider.authenticate(request("bad"), context).await().indefinitely()
        }
    }

    @Test
    fun `Given an expired token, Then it throws AuthenticationFailedException`() {
        // Spec §12 fallback: SESSION_EXPIRED could not be routed through a JAX-RS mapper at runtime
        // (verified in Task 9), so the provider collapses expired and invalid tokens to the same
        // AuthenticationFailedException. The distinction still lives in SessionTokenAuthenticator.
        every { authenticator.authenticate("old") } throws SessionTokenExpiredError()
        assertThrows<AuthenticationFailedException> {
            provider.authenticate(request("old"), context).await().indefinitely()
        }
    }
}
