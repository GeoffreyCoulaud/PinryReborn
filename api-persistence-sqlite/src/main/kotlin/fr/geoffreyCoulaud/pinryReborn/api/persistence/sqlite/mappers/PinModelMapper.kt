package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.PinModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.ModelCursor

object PinModelMapper {
    fun Pin.toModel(): PinModel =
        PinModel(
            id = id,
            author = author.toModel(),
            sourceContextUrl = sourceContextUrl,
            sourceMediaUrl = sourceMediaUrl,
            description = description,
            softDeletedAt = softDeletedAt,
        )

    fun PinModel.toDomain(
        tags: List<Tag>,
        boards: List<Board>,
    ): Pin =
        Pin(
            id = id,
            author = author.toDomain(),
            sourceContextUrl = sourceContextUrl,
            sourceMediaUrl = sourceMediaUrl,
            description = description,
            tags = tags,
            boards = boards,
            softDeletedAt = softDeletedAt,
            createdAt = whenCreated,
            updatedAt = whenModified,
        )

    fun ModelCursor<PinModel>.toDomain(): Cursor =
        Cursor(
            pivotId = this.pivot.id,
            direction = this.direction,
        )
}
