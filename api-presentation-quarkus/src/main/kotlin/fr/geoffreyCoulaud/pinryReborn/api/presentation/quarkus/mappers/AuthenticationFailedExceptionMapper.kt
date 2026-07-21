package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenExpiredError
import io.quarkus.security.AuthenticationFailedException
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
@Priority(Priorities.AUTHENTICATION)
class AuthenticationFailedExceptionMapper : ExceptionMapper<AuthenticationFailedException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: AuthenticationFailedException): Response {
        val (code, detail) = describe(exception)
        return problemResponse(
            status = Response.Status.UNAUTHORIZED,
            detail = detail,
            code = code,
            uriInfo = uriInfo,
        ).header("WWW-Authenticate", WWW_AUTHENTICATE_BEARER).build()
    }

    // Cause-inspection lives here (not in a mapped subtype): a subtype of the final
    // AuthenticationFailedException never reached this chain at runtime (see BearerTokenIdentityProvider).
    private fun describe(exception: AuthenticationFailedException): Pair<String, String> =
        if (exception.cause is SessionTokenExpiredError) {
            SESSION_EXPIRED_CODE to "Session expired"
        } else {
            AUTHENTICATION_FAILED_CODE to "Authentication failed"
        }

    private companion object {
        const val SESSION_EXPIRED_CODE = "SESSION_EXPIRED"
        const val AUTHENTICATION_FAILED_CODE = "AUTHENTICATION_FAILED"
    }
}
