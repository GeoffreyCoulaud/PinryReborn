package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ProblemDetail
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo

/** RFC 7807 challenge value: opaque bearer token, no realm. */
const val WWW_AUTHENTICATE_BEARER = "Bearer"

/** Build an RFC 7807 problem+json response builder. Callers may add headers before build(). */
fun problemResponse(
    status: Response.Status,
    detail: String?,
    code: String,
    uriInfo: UriInfo,
): Response.ResponseBuilder = problemResponse(status.statusCode, status.reasonPhrase, detail, code, uriInfo)

/**
 * Build an RFC 7807 problem+json response builder for a raw status code, for statuses with no
 * matching [Response.Status] constant (e.g. 422 Unprocessable Entity, not part of jakarta.ws.rs 4.0).
 * Callers may add headers before build().
 */
fun problemResponse(
    status: Int,
    title: String,
    detail: String?,
    code: String,
    uriInfo: UriInfo,
): Response.ResponseBuilder =
    Response
        .status(status)
        .entity(
            ProblemDetail(
                title = title,
                status = status,
                detail = detail,
                instance = uriInfo.path,
                code = code,
            ),
        )
        .type(PROBLEM_JSON_MEDIA_TYPE)
