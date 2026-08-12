package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.domain.users.UsernameAlreadyTakenException
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UsernameAlreadyTakenError
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class UserCreator(
    private val userRepository: UserRepositoryInterface,
    private val userPasswordRepository: UserPasswordHashRepositoryInterface,
    private val passwordHasher: PasswordHasher,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    fun createUserWithPassword(
        name: String,
        password: String,
    ): User =
        transactionRunner.inTransaction {
            val user = saveUser(name)
            userPasswordRepository.saveUserPasswordHash(
                user = user,
                hashedPassword = passwordHasher.hash(password, clock.now()),
            )
            user
        }

    private fun saveUser(name: String): User {
        val user = User(id = UUID.randomUUID(), name = name.trim(), createdAt = clock.now())
        // The index is the sole authority on the name being free, case and tombstones included: no read here.
        return try {
            userRepository.saveUser(user)
        } catch (error: UsernameAlreadyTakenException) {
            throw UsernameAlreadyTakenError(error)
        }
    }
}
