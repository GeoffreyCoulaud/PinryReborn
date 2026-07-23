package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.http.RangeNotSatisfiableException
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/**
 * `416 Requested Range Not Satisfiable`, with a `Content-Range` header naming the resource's total
 * size (spec `docs/specs/2026-07-22-user-data-export.md` §7) so the client can retry correctly.
 */
@Provider
class RangeNotSatisfiableExceptionMapper : ExceptionMapper<RangeNotSatisfiableException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: RangeNotSatisfiableException): Response =
        problemResponse(
            status = Response.Status.REQUESTED_RANGE_NOT_SATISFIABLE,
            detail = exception.message,
            code = RANGE_NOT_SATISFIABLE_CODE,
            uriInfo = uriInfo,
        ).header("Content-Range", "bytes */${exception.totalSize}").build()

    private companion object {
        const val RANGE_NOT_SATISFIABLE_CODE = "RANGE_NOT_SATISFIABLE"
    }
}
