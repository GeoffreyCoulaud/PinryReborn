package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ReauthenticationError
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class Reauthenticator(
    private val userPasswordRepository: UserPasswordHashRepositoryInterface,
    private val passwordHasher: PasswordHasher,
) {
    fun reauthenticate(user: User, factor: String) {
        val hash = userPasswordRepository.findCurrentPasswordHash(user)
        if (hash == null || !passwordHasher.matches(factor, hash)) throw ReauthenticationError()
    }
}
