package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

import java.util.UUID

data class BoardOutputDto(
    val id: UUID,
    val name: String,
    val description: String,
    val pinCount: Int,
)
