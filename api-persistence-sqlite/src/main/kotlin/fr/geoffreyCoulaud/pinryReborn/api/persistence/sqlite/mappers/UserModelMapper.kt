package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserModel

object UserModelMapper {
    // The recycling instant is mapped both ways. Omitting it here would let any save of a
    // tombstoned account read back from the store silently resurrect it, the column being the
    // whole record of the tombstone.
    fun User.toModel() =
        UserModel(
            id = id,
            name = name,
            createdAt = createdAt,
            softDeletedAt = softDeletedAt,
        )

    fun UserModel.toDomain() =
        User(
            id = id,
            name = name,
            createdAt = createdAt,
            softDeletedAt = softDeletedAt,
        )
}
