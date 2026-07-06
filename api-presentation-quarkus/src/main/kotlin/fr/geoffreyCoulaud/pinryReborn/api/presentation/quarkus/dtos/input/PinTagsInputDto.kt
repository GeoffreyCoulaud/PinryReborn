package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input

import jakarta.validation.constraints.NotBlank

data class PinTagsInputDto(
    val tags: List<@NotBlank String>,
)
