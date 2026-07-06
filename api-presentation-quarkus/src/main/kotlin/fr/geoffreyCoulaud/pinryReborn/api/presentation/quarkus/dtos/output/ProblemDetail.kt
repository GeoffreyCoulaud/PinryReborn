package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

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
)
