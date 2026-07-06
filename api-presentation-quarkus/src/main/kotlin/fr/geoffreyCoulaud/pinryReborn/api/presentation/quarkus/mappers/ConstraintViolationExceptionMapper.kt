package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

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
        val detail = exception.constraintViolations
            .joinToString(separator = "; ") { "${it.propertyPath}: ${it.message}" }
        return problemResponse(Response.Status.BAD_REQUEST, detail, VALIDATION_ERROR_CODE, uriInfo).build()
    }

    companion object {
        const val VALIDATION_ERROR_CODE = "VALIDATION_ERROR"
    }
}
