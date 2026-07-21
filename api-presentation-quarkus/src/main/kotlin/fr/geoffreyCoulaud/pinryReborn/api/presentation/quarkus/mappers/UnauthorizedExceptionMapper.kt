package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import io.quarkus.security.UnauthorizedException
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
@Priority(Priorities.AUTHENTICATION)
class UnauthorizedExceptionMapper : ExceptionMapper<UnauthorizedException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: UnauthorizedException): Response =
        problemResponse(
            status = Response.Status.UNAUTHORIZED,
            detail = "Authentication required",
            code = "AUTHENTICATION_REQUIRED",
            uriInfo = uriInfo,
        ).header("WWW-Authenticate", WWW_AUTHENTICATE_BEARER).build()
}
