package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ProblemDetail
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo

/** RFC 7807 challenge value shared by the auth mappers. */
const val WWW_AUTHENTICATE_BASIC = "Basic realm=\"Quarkus\""

/** Build an RFC 7807 problem+json response builder. Callers may add headers before build(). */
fun problemResponse(
    status: Response.Status,
    detail: String?,
    code: String,
    uriInfo: UriInfo,
): Response.ResponseBuilder =
    Response
        .status(status)
        .entity(
            ProblemDetail(
                title = status.reasonPhrase,
                status = status.statusCode,
                detail = detail,
                instance = uriInfo.path,
                code = code,
            ),
        )
        .type(PROBLEM_JSON_MEDIA_TYPE)
