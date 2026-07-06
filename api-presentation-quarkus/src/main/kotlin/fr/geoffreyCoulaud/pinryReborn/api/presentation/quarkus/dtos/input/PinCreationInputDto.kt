package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class PinCreationInputDto(
    @field:NotBlank
    val sourceContextUrl: String,
    @field:NotBlank
    val sourceMediaUrl: String,
    @field:Size(max = 2000)
    val description: String,
)
