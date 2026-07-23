package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserModel

object UserModelMapper {
    // toModel never sets `deleted`: new users are active.
    // Transitions go only through markPendingDeletion/permanentlyDeleteUser.
    fun User.toModel() =
        UserModel(
            id = id,
            name = name,
            createdAt = createdAt,
        )

    fun UserModel.toDomain() =
        User(
            id = id,
            name = name,
            createdAt = createdAt,
            softDeleted = deleted,
        )
}
