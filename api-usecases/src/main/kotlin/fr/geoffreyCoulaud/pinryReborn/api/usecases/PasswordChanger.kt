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
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ThrottledError
import java.time.Duration

@Suppress("LongParameterList")
class PasswordChanger(
    private val userPasswordRepository: UserPasswordHashRepositoryInterface,
    private val passwordHasher: PasswordHasher,
    private val sessionRevoker: SessionRevoker,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
    private val attemptLimiter: AuthenticationAttemptLimiter,
    private val minimumInterval: Duration,
) {
    // Each throw is a distinct domain refusal (bad reauth, too soon, reused, concurrent collision);
    // collapsing them would hide the rule, so the count is intentional.
    @Suppress("ThrowsCount")
    fun changePassword(user: User, currentPassword: String, newPassword: String) {
        // The counter Reauthenticator shares: it is the same secret (spec D4).
        val attemptKey = AuthenticationAttemptKey.forUser(user.id)
        attemptLimiter.check(attemptKey)
        val current = userPasswordRepository.findCurrentPasswordHash(user)
        if (current == null || !passwordHasher.matches(currentPassword, current)) {
            attemptLimiter.recordFailure(attemptKey)
            throw ReauthenticationError()
        }
        // Cleared here rather than at the end: the counter limits password guesses, and a change
        // refused by a rule below was not one.
        attemptLimiter.recordSuccess(attemptKey)
        val now = clock.now()
        val earliest = now.minus(minimumInterval)
        if (current.createdAt.isAfter(earliest)) {
            throw PasswordChangedTooSoonError(ThrottledError.wholeSecondsBetween(earliest, current.createdAt))
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
