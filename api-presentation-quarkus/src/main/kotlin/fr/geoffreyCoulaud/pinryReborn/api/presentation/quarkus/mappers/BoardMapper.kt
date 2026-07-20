package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.BoardOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.BoardRefDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.RecycledBoardDto

object BoardMapper {
    fun Board.toRefDto() = BoardRefDto(id = id, name = name)

    fun Board.toDto(pinCount: Int) =
        BoardOutputDto(id = id, name = name, description = description, pinCount = pinCount)

    fun Board.toRecycledDto() = RecycledBoardDto(id = id, name = name, description = description)
}
