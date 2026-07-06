package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ProblemDetail
import jakarta.validation.ConstraintViolationException
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class ConstraintViolationExceptionMapper : ExceptionMapper<ConstraintViolationException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: ConstraintViolationException): Response {
        val status = Response.Status.BAD_REQUEST
        val detail = exception.constraintViolations
            .joinToString(separator = "; ") { "${it.propertyPath}: ${it.message}" }
        val problem = ProblemDetail(
            title = status.reasonPhrase,
            status = status.statusCode,
            detail = detail,
            instance = uriInfo.path,
            code = VALIDATION_ERROR_CODE,
        )
        return Response
            .status(status)
            .entity(problem)
            .type(PROBLEM_JSON_MEDIA_TYPE)
            .build()
    }

    companion object {
        const val VALIDATION_ERROR_CODE = "VALIDATION_ERROR"
    }
}
