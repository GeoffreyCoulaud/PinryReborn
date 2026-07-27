package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class ReapExpiredSessionTokens(
    private val sessionTokenRepository: SessionTokenRepositoryInterface,
    private val clock: Clock,
) {
    fun reap(): Int = sessionTokenRepository.deleteExpiredBefore(clock.now())
}
