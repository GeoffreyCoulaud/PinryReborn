package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.UserOutputDto

object UserDtoMapper {
    fun User.toDto() =
        UserOutputDto(
            id = id,
            name = name,
        )
}
