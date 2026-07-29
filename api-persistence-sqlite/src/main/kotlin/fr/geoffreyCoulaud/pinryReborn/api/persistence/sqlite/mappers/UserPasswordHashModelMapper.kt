package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserPasswordHashModel

object UserPasswordHashModelMapper {
    fun UserPasswordHashModel.toDomain(): HashedPassword =
        HashedPassword(
            hash = hash,
            algorithm = algorithm,
            createdAt = createdAt,
        )

    fun HashedPassword.toModel(userModel: UserModel): UserPasswordHashModel =
        UserPasswordHashModel(
            user = userModel,
            hash = hash,
            algorithm = algorithm,
            createdAt = createdAt,
        )
}
