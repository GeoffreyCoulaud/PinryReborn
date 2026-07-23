package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.TagModel

object TagModelMapper {
    fun Tag.toModel() =
        TagModel(
            id = id,
            author = author.toModel(),
            name = name,
            createdAt = createdAt,
        )

    fun TagModel.toDomain() =
        Tag(
            id = id,
            author = author.toDomain(),
            name = name,
            createdAt = createdAt,
        )
}
