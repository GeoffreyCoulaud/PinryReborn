package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.SessionTokenModel

object SessionTokenModelMapper {
    fun SessionTokenModel.toDomain() =
        SessionToken(
            id = id,
            user = user.toDomain(),
            expiresAt = expiresAt,
            persistent = persistent,
        )
}
