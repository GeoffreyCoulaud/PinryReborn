package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenExpiredError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenInvalidError
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class SessionTokenAuthenticator(
    private val sessionTokenRepository: SessionTokenRepositoryInterface,
    private val clock: Clock,
) {
    fun authenticate(token: String): SessionToken {
        val sessionToken = sessionTokenRepository.findByTokenHash(TokenHasher.sha256(token))
            ?: throw SessionTokenInvalidError()
        if (!sessionToken.expiresAt.isAfter(clock.now())) throw SessionTokenExpiredError()
        return sessionToken
    }
}
