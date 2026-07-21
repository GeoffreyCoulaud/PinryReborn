package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User

interface UserPasswordHashRepositoryInterface {
    fun saveUserPasswordHash(
        user: User,
        hashedPassword: HashedPassword,
    ): HashedPassword

    fun findCurrentPasswordHash(user: User): HashedPassword?

    fun findAllPasswordHashesForUser(user: User): List<HashedPassword>

    fun deleteForUser(user: User)
}
