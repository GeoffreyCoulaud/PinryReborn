package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PasswordPreviouslyUsedError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ReauthenticationError
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class PasswordChanger(
    private val userPasswordRepository: UserPasswordHashRepositoryInterface,
    private val passwordHasher: PasswordHasher,
    private val sessionRevoker: SessionRevoker,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    fun changePassword(user: User, currentPassword: String, newPassword: String) {
        val current = userPasswordRepository.findCurrentPasswordHash(user)
        if (current == null || !passwordHasher.matches(currentPassword, current)) throw ReauthenticationError()
        val history = userPasswordRepository.findAllPasswordHashesForUser(user)
        if (history.any { passwordHasher.matches(newPassword, it) }) throw PasswordPreviouslyUsedError()
        transactionRunner.inTransaction {
            userPasswordRepository.saveUserPasswordHash(user, passwordHasher.hash(newPassword, clock.now()))
            sessionRevoker.revokeAll(user)
        }
    }
}
