package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ProblemDetail
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BaseError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ErrorCode
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class BaseErrorMapper : ExceptionMapper<BaseError> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: BaseError): Response {
        val status = statusFor(exception.code)
        val problem = ProblemDetail(
            title = status.reasonPhrase,
            status = status.statusCode,
            detail = exception.message,
            instance = uriInfo.path,
            code = exception.code.name,
        )
        return Response
            .status(status)
            .entity(problem)
            .type(PROBLEM_JSON)
            .build()
    }

    private fun statusFor(code: ErrorCode): Response.Status =
        when (code) {
            ErrorCode.USERNAME_ALREADY_EXISTS -> Response.Status.CONFLICT
            ErrorCode.PIN_DOES_NOT_EXIST -> Response.Status.NOT_FOUND
            ErrorCode.PIN_INSUFFICIENT_PERMISSIONS -> Response.Status.FORBIDDEN
            ErrorCode.PIN_NOT_SOFT_DELETED -> Response.Status.CONFLICT
            ErrorCode.PIN_ALREADY_SOFT_DELETED -> Response.Status.CONFLICT
            ErrorCode.SEARCH_EMPTY_QUERY -> Response.Status.BAD_REQUEST
            ErrorCode.INVALID_LOGIN -> Response.Status.BAD_REQUEST
            ErrorCode.USER_DOES_NOT_EXIST -> Response.Status.UNAUTHORIZED
            ErrorCode.INVALID_PASSWORD -> Response.Status.UNAUTHORIZED
            ErrorCode.INVALID_HTTP_AUTHORIZATION_SCHEME -> Response.Status.UNAUTHORIZED
        }

    companion object {
        const val PROBLEM_JSON = "application/problem+json"
    }
}
