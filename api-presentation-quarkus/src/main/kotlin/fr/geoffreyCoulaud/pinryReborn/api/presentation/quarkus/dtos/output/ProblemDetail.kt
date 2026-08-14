package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * RFC 7807 Problem Details (application/problem+json).
 * [code] est un membre d'extension portant le nom du code d'erreur applicatif.
 */
data class ProblemDetail(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String?,
    val instance: String,
    val code: String,
    /** Extension member of one refusal only, so it is absent rather than null everywhere else. */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val currentLength: Long? = null,
)
