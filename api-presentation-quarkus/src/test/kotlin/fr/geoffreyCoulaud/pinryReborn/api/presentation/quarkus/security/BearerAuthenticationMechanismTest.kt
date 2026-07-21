package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security

import io.quarkus.security.identity.IdentityProviderManager
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.request.TokenAuthenticationRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import io.vertx.core.http.HttpServerRequest
import io.vertx.ext.web.RoutingContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BearerAuthenticationMechanismTest {
    private val mechanism = BearerAuthenticationMechanism()
    private val idpManager = mockk<IdentityProviderManager>()

    private fun contextWithHeader(value: String?): RoutingContext {
        val request = mockk<HttpServerRequest> { every { getHeader("Authorization") } returns value }
        return mockk { every { request() } returns request }
    }

    @Test
    fun `Given a Bearer header, Then it authenticates the extracted token`() {
        val identity = mockk<SecurityIdentity>()
        val captured = slot<TokenAuthenticationRequest>()
        every { idpManager.authenticate(capture(captured)) } returns Uni.createFrom().item(identity)

        val result = mechanism.authenticate(contextWithHeader("Bearer abc.def"), idpManager).await().indefinitely()

        assertEquals(identity, result)
        assertEquals("abc.def", captured.captured.token.token)
    }

    @Test
    fun `Given a lowercase bearer header, Then it authenticates the extracted token`() {
        val identity = mockk<SecurityIdentity>()
        val captured = slot<TokenAuthenticationRequest>()
        every { idpManager.authenticate(capture(captured)) } returns Uni.createFrom().item(identity)

        val result = mechanism.authenticate(contextWithHeader("bearer abc.def"), idpManager).await().indefinitely()

        assertEquals(identity, result)
        assertEquals("abc.def", captured.captured.token.token)
    }

    @Test
    fun `Given no Authorization header, Then it returns a null identity (anonymous)`() {
        assertNull(mechanism.authenticate(contextWithHeader(null), idpManager).await().indefinitely())
    }

    @Test
    fun `Given a non-Bearer header, Then it returns a null identity (anonymous)`() {
        assertNull(mechanism.authenticate(contextWithHeader("Basic dXNlcjpwdw=="), idpManager).await().indefinitely())
    }

    @Test
    fun `Given getChallenge, Then it is a 401 Bearer challenge`() {
        val challenge = mechanism.getChallenge(contextWithHeader(null)).await().indefinitely()
        assertEquals(401, challenge.status)
    }

    @Test
    fun `Given getCredentialTypes, Then it is TokenAuthenticationRequest`() {
        assertTrue(mechanism.getCredentialTypes().contains(TokenAuthenticationRequest::class.java))
    }
}
