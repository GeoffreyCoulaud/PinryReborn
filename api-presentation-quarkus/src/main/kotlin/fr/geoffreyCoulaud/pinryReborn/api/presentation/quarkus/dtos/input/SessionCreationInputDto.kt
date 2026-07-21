package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input

import jakarta.validation.constraints.NotBlank

/**
 * Login body. Only [name]/[password] are `@NotBlank`; registration's size/pattern constraints are
 * deliberately NOT re-applied, so a badly-shaped credential fails as 401 (auth), never 400
 * (validation). [rememberMe] is nullable (absent JSON -> null, coalesced to false in the controller);
 * a non-null default would trip Kover's synthetic-constructor-branch check.
 */
data class SessionCreationInputDto(
    @field:NotBlank
    val name: String,
    @field:NotBlank
    val password: String,
    val rememberMe: Boolean?,
)
