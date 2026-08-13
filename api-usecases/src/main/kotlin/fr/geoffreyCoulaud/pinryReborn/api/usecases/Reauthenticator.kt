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
    private val attemptLimiter: AuthenticationAttemptLimiter,
) {
    fun reauthenticate(user: User, factor: String) {
        // The counter PasswordChanger shares: it is the same secret (spec D4).
        val attemptKey = AuthenticationAttemptKey.forUser(user.id)
        attemptLimiter.check(attemptKey)
        val hash = userPasswordRepository.findCurrentPasswordHash(user)
        if (hash == null || !passwordHasher.matches(factor, hash)) {
            attemptLimiter.recordFailure(attemptKey)
            throw ReauthenticationError()
        }
        attemptLimiter.recordSuccess(attemptKey)
    }
}
