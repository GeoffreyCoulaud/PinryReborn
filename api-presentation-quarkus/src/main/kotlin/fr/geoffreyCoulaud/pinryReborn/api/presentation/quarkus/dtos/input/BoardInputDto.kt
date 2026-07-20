package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class BoardInputDto(
    @field:NotBlank
    @field:Size(max = 200)
    val name: String,
    @field:Size(max = 2000)
    val description: String,
)
