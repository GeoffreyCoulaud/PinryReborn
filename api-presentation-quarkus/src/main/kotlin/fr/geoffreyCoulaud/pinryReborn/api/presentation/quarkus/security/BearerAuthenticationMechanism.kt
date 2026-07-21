package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security

import io.quarkus.security.credential.TokenCredential
import io.quarkus.security.identity.IdentityProviderManager
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.request.AuthenticationRequest
import io.quarkus.security.identity.request.TokenAuthenticationRequest
import io.quarkus.vertx.http.runtime.security.ChallengeData
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport
import io.smallrye.mutiny.Uni
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

@ApplicationScoped
class BearerAuthenticationMechanism : HttpAuthenticationMechanism {
    override fun authenticate(
        context: RoutingContext,
        identityProviderManager: IdentityProviderManager,
    ): Uni<SecurityIdentity> {
        val header = context.request().getHeader("Authorization")
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return Uni.createFrom().nullItem()
        }
        val token = header.substring(BEARER_PREFIX.length)
        return identityProviderManager.authenticate(TokenAuthenticationRequest(TokenCredential(token, "bearer")))
    }

    override fun getChallenge(context: RoutingContext): Uni<ChallengeData> =
        Uni.createFrom().item(ChallengeData(Response.Status.UNAUTHORIZED.statusCode, "WWW-Authenticate", "Bearer"))

    override fun getCredentialTypes(): Set<Class<out AuthenticationRequest>> =
        setOf(TokenAuthenticationRequest::class.java)

    override fun getCredentialTransport(context: RoutingContext): Uni<HttpCredentialTransport> =
        Uni.createFrom().item(HttpCredentialTransport(HttpCredentialTransport.Type.AUTHORIZATION, "bearer"))

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}
