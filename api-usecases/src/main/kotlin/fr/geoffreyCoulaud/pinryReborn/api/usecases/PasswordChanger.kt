package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordChangeCollisionException
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PasswordChangedTooSoonError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PasswordChangeCollisionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PasswordPreviouslyUsedError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ReauthenticationError
import java.time.Duration

@Suppress("LongParameterList")
class PasswordChanger(
    private val userPasswordRepository: UserPasswordHashRepositoryInterface,
    private val passwordHasher: PasswordHasher,
    private val sessionRevoker: SessionRevoker,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
    private val minimumInterval: Duration,
) {
    // Each throw is a distinct domain refusal (bad reauth, too soon, reused, concurrent collision);
    // collapsing them would hide the rule, so the count is intentional.
    @Suppress("ThrowsCount")
    fun changePassword(user: User, currentPassword: String, newPassword: String) {
        val current = userPasswordRepository.findCurrentPasswordHash(user)
        if (current == null || !passwordHasher.matches(currentPassword, current)) throw ReauthenticationError()
        val now = clock.now()
        if (current.createdAt.isAfter(now.minus(minimumInterval))) {
            val retryAfterSeconds =
                Duration.between(now.minus(minimumInterval), current.createdAt).seconds.coerceAtLeast(1)
            throw PasswordChangedTooSoonError(retryAfterSeconds)
        }
        val history = userPasswordRepository.findAllPasswordHashesForUser(user)
        if (history.any { passwordHasher.matches(newPassword, it) }) throw PasswordPreviouslyUsedError()
        transactionRunner.inTransaction {
            try {
                userPasswordRepository.saveUserPasswordHash(user, passwordHasher.hash(newPassword, now))
            } catch (error: PasswordChangeCollisionException) {
                throw PasswordChangeCollisionError(error)
            }
            sessionRevoker.revokeAll(user)
        }
    }
}
