package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security

import fr.geoffreyCoulaud.pinryReborn.api.usecases.SessionTokenAuthenticator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenExpiredError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenInvalidError
import io.quarkus.security.AuthenticationFailedException
import io.quarkus.security.identity.AuthenticationRequestContext
import io.quarkus.security.identity.IdentityProvider
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.request.TokenAuthenticationRequest
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class BearerTokenIdentityProvider(
    private val sessionTokenAuthenticator: SessionTokenAuthenticator,
) : IdentityProvider<TokenAuthenticationRequest> {
    override fun getRequestType(): Class<TokenAuthenticationRequest> = TokenAuthenticationRequest::class.java

    override fun authenticate(
        request: TokenAuthenticationRequest,
        context: AuthenticationRequestContext,
    ): Uni<SecurityIdentity> =
        context.runBlocking {
            try {
                val session = sessionTokenAuthenticator.authenticate(request.token.token)
                QuarkusSecurityIdentity
                    .builder()
                    .setPrincipal { session.user.name }
                    .addAttribute("userId", session.user.id)
                    .addAttribute("user", session.user)
                    .addAttribute("sessionToken", session)
                    .build()
            } catch (e: SessionTokenExpiredError) {
                // Spec §12: a dedicated SessionExpiredException subtype was tried (Task 9) but Quarkus
                // never routes an AuthenticationCompletionException subtype through the JAX-RS
                // exception-mapper chain at runtime (it short-circuits with a bodyless 401 before
                // RESTEasy Reactive sees it). AuthenticationFailedException IS proven to route, so both
                // branches throw that same (final) type; AuthenticationFailedExceptionMapper inspects
                // the cause to still emit a distinct SESSION_EXPIRED code for this branch.
                throw AuthenticationFailedException("Session token expired", e)
            } catch (e: SessionTokenInvalidError) {
                throw AuthenticationFailedException("Invalid session token", e)
            }
        }
}
