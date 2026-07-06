package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class UserInputDto(
    @field:NotBlank
    @field:Size(min = 3, max = 50)
    @field:Pattern(regexp = "^[A-Za-z0-9._-]+$")
    val name: String,
    @field:NotBlank
    @field:Size(min = 8, max = 72)
    val password: String,
)
