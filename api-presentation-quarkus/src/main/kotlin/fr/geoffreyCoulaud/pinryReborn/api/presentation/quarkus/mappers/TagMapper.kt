package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.TagOutputDto

object TagMapper {
    fun Tag.toDto() = TagOutputDto(
        name = name
    )
}
