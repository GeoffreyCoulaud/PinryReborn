package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input

import java.util.UUID

data class PinBoardsInputDto(
    val boardIds: List<UUID>,
)
