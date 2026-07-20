package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

import java.util.UUID

data class RecycledBoardDto(val id: UUID, val name: String, val description: String)

data class RecycledBoardListOutputDto(val boards: List<RecycledBoardDto>)
