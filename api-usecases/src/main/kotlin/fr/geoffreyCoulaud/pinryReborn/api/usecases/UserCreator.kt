package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UsernameAlreadyTakenError
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.UUID

@ApplicationScoped
class UserCreator(
    private val userRepository: UserRepositoryInterface,
    private val userPasswordRepository: UserPasswordHashRepositoryInterface,
    private val passwordHasher: PasswordHasher,
) {
    @Transactional
    fun createUser(name: String): User {
        val normalizedName = name.trim()
        // Check that the username is free (case-insensitive via the repository lookup)
        val existingUser = userRepository.findUserByName(normalizedName)
        if (existingUser != null) throw UsernameAlreadyTakenError()
        // Create the user
        val user = User(id = UUID.randomUUID(), name = normalizedName)
        return userRepository.saveUser(user)
    }

    @Transactional
    fun createUserWithPassword(
        name: String,
        password: String,
    ): User {
        // Create the user as usual
        val user = createUser(name)
        // Hash and save the password
        userPasswordRepository.saveUserPasswordHash(user = user, hashedPassword = passwordHasher.hash(password))
        return user
    }
}
