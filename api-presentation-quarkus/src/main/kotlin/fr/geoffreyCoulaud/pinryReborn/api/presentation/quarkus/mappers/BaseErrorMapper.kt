package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

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
        val resolvedStatus = Response.Status.fromStatusCode(status)
        // Fall back for status codes with no matching Response.Status constant (e.g. 422
        // Unprocessable Entity, not part of jakarta.ws.rs 4.0's Response.Status enum).
        val title = if (resolvedStatus == null) UNPROCESSABLE_ENTITY_TITLE else resolvedStatus.reasonPhrase
        return problemResponse(status, title, exception.message, exception.code.name, uriInfo).build()
    }

    /** Returns the raw HTTP status code, since not every mapped status has a [Response.Status] constant. */
    // Flat one-arm-per-ErrorCode dispatch table, not nested branching; the exhaustive `when` (no
    // `else`) is intentional so a future ErrorCode without a mapped status fails to compile.
    @Suppress("CyclomaticComplexMethod")
    private fun statusFor(code: ErrorCode): Int =
        when (code) {
            ErrorCode.USERNAME_ALREADY_EXISTS -> Response.Status.CONFLICT.statusCode
            ErrorCode.PIN_DOES_NOT_EXIST -> Response.Status.NOT_FOUND.statusCode
            ErrorCode.PIN_INSUFFICIENT_PERMISSIONS -> Response.Status.FORBIDDEN.statusCode
            ErrorCode.PIN_NOT_SOFT_DELETED -> Response.Status.CONFLICT.statusCode
            ErrorCode.PIN_ALREADY_SOFT_DELETED -> Response.Status.CONFLICT.statusCode
            ErrorCode.SEARCH_EMPTY_QUERY -> Response.Status.BAD_REQUEST.statusCode
            ErrorCode.INVALID_LOGIN -> Response.Status.BAD_REQUEST.statusCode
            ErrorCode.USER_DOES_NOT_EXIST -> Response.Status.UNAUTHORIZED.statusCode
            ErrorCode.INVALID_PASSWORD -> Response.Status.UNAUTHORIZED.statusCode
            ErrorCode.INVALID_HTTP_AUTHORIZATION_SCHEME -> Response.Status.UNAUTHORIZED.statusCode
            ErrorCode.IMAGE_DOES_NOT_EXIST -> Response.Status.NOT_FOUND.statusCode
            ErrorCode.IMAGE_INSUFFICIENT_PERMISSIONS -> Response.Status.FORBIDDEN.statusCode
            ErrorCode.IMAGE_TOO_LARGE -> Response.Status.REQUEST_ENTITY_TOO_LARGE.statusCode
            ErrorCode.IMAGE_INVALID -> UNPROCESSABLE_ENTITY_STATUS_CODE
            ErrorCode.IMAGE_SOURCE_URL_INVALID -> Response.Status.BAD_REQUEST.statusCode
            ErrorCode.IMAGE_RENDITION_SIZE_INVALID -> Response.Status.BAD_REQUEST.statusCode
            ErrorCode.BOARD_DOES_NOT_EXIST -> Response.Status.NOT_FOUND.statusCode
            ErrorCode.BOARD_INSUFFICIENT_PERMISSIONS -> Response.Status.FORBIDDEN.statusCode
            ErrorCode.BOARD_NOT_SOFT_DELETED -> Response.Status.CONFLICT.statusCode
            ErrorCode.BOARD_ALREADY_SOFT_DELETED -> Response.Status.CONFLICT.statusCode
            ErrorCode.BOARD_INVALID_MEMBERSHIP -> Response.Status.BAD_REQUEST.statusCode
            ErrorCode.REAUTHENTICATION_FAILED -> Response.Status.FORBIDDEN.statusCode
            ErrorCode.PASSWORD_PREVIOUSLY_USED -> UNPROCESSABLE_ENTITY_STATUS_CODE
            ErrorCode.UNSUPPORTED_REAUTHENTICATION_FACTOR -> Response.Status.BAD_REQUEST.statusCode
            ErrorCode.EXPORT_ALREADY_IN_PROGRESS -> Response.Status.CONFLICT.statusCode
            ErrorCode.EXPORT_TOO_SOON -> Response.Status.TOO_MANY_REQUESTS.statusCode
            ErrorCode.EXPORT_DOES_NOT_EXIST -> Response.Status.NOT_FOUND.statusCode
            ErrorCode.EXPORT_INSUFFICIENT_PERMISSIONS -> Response.Status.FORBIDDEN.statusCode
            ErrorCode.EXPORT_NOT_READY -> Response.Status.CONFLICT.statusCode
            ErrorCode.EXPORT_GONE -> Response.Status.GONE.statusCode
        }

    private companion object {
        // jakarta.ws.rs 4.0's Response.Status has no UNPROCESSABLE_ENTITY constant (RFC 9110 422).
        const val UNPROCESSABLE_ENTITY_STATUS_CODE = 422
        const val UNPROCESSABLE_ENTITY_TITLE = "Unprocessable Entity"
    }
}
