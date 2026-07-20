package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.BoardModel

object BoardModelMapper {
    fun Board.toModel() =
        BoardModel(
            id = id,
            author = author.toModel(),
            name = name,
            description = description,
            softDeletedAt = softDeletedAt,
        )

    fun BoardModel.toDomain() =
        Board(
            id = id,
            author = author.toDomain(),
            name = name,
            description = description,
            softDeletedAt = softDeletedAt,
        )
}
