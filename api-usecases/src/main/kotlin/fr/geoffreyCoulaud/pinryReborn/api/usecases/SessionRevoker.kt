package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class SessionRevoker(
    private val sessionTokenRepository: SessionTokenRepositoryInterface,
) {
    fun revokeCurrent(current: SessionToken) = sessionTokenRepository.deleteById(current.id)

    fun revokeAll(user: User) = sessionTokenRepository.deleteAllForUser(user.id)
}
