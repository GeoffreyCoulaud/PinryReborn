package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.SessionExpiredException
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

// Higher priority than AuthenticationFailedExceptionMapper so the SESSION_EXPIRED subtype wins.
@Provider
@Priority(Priorities.AUTHENTICATION - 1)
class SessionExpiredExceptionMapper : ExceptionMapper<SessionExpiredException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: SessionExpiredException): Response =
        problemResponse(
            status = Response.Status.UNAUTHORIZED,
            detail = "Session expired",
            code = "SESSION_EXPIRED",
            uriInfo = uriInfo,
        ).header("WWW-Authenticate", WWW_AUTHENTICATE_BEARER).build()
}
