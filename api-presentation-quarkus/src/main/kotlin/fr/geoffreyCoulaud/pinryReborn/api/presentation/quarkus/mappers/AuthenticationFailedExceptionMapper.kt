package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ProblemDetail
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
        val status = Response.Status.UNAUTHORIZED
        val problem = ProblemDetail(
            title = status.reasonPhrase,
            status = status.statusCode,
            detail = "Invalid username or password",
            instance = uriInfo.path,
            code = "AUTHENTICATION_FAILED",
        )
        return Response
            .status(status)
            .header("WWW-Authenticate", "Basic realm=\"Quarkus\"")
            .entity(problem)
            .type("application/problem+json")
            .build()
    }
}
