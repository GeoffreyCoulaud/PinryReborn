package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ExportTooSoonError
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/**
 * More specific than [BaseErrorMapper] for [ExportTooSoonError]: JAX-RS resolves the exception
 * mapper whose type parameter is the nearest match to the thrown exception's exact class, so this
 * wins over the generic `ExceptionMapper<BaseError>` and adds the `Retry-After` header the generic
 * mapper has no way to know about (spec `docs/specs/2026-07-22-user-data-export.md` §7, §11).
 */
@Provider
class ExportTooSoonExceptionMapper : ExceptionMapper<ExportTooSoonError> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: ExportTooSoonError): Response =
        problemResponse(
            status = Response.Status.TOO_MANY_REQUESTS,
            detail = exception.message,
            code = exception.code.name,
            uriInfo = uriInfo,
        ).header("Retry-After", exception.retryAfterSeconds).build()
}
