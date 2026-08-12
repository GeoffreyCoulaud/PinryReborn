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
    fun createUser(name: String): User = transactionRunner.inTransaction { createUserInternal(name) }

    fun createUserWithPassword(
        name: String,
        password: String,
    ): User =
        transactionRunner.inTransaction {
            // Create the user as usual
            val user = createUserInternal(name)
            // Hash and save the password
            userPasswordRepository.saveUserPasswordHash(
                user = user,
                hashedPassword = passwordHasher.hash(password, clock.now()),
            )
            user
        }

    private fun createUserInternal(name: String): User {
        val normalizedName = name.trim()
        val user = User(id = UUID.randomUUID(), name = normalizedName, createdAt = clock.now())
        // The index is the sole authority on the name being free, case and tombstones included: no read here.
        return try {
            userRepository.saveUser(user)
        } catch (error: UsernameAlreadyTakenException) {
            throw UsernameAlreadyTakenError(error)
        }
    }
}
