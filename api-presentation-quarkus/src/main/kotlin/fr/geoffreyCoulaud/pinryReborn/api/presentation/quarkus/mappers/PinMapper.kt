package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.PaginationOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.PinListOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.PinOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.BoardMapper.toRefDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.CursorMapper.toDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.TagMapper.toDto

object PinMapper {
    fun Pin.toDto() = PinOutputDto(
        id = id,
        authorId = author.id,
        sourceContextUrl = sourceContextUrl,
        sourceMediaUrl = sourceMediaUrl,
        description = description,
        tags = tags.map { it.toDto() },
        boards = boards.map { it.toRefDto() },
        softDeletedAt = softDeletedAt,
    )

    fun Page<Pin>.toDto() = PinListOutputDto(
        pins = this.items.map { it.toDto() },
        pagination = PaginationOutputDto(
            previousCursor = this.previousCursor?.toDto(),
            nextCursor = this.nextCursor?.toDto(),
        ),
    )
}


