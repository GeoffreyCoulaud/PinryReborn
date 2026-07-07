package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.exceptions.UserModelDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserPasswordHashRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserRepository
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class UserPasswordHashRepositoryTest : RepositoryTest() {
    private val repository = UserPasswordHashRepository(database)
    private val userRepository = UserRepository(database)

    private fun createAndSaveUser(): User =
        userRepository.saveUser(
            User(id = randomUUID(), name = createRandomString()),
        )

    @Test
    fun `Given a saved password hash, Then findUserPasswordHash returns it`() {
        // Given
        val user = createAndSaveUser()
        val hashedPassword = HashedPassword(hash = "hash", algorithm = PasswordHashAlgorithm.BCRYPT)
        repository.saveUserPasswordHash(user, hashedPassword)

        // When
        val found = repository.findUserPasswordHash(user)

        // Then
        assertNotNull(found)
        assertEquals(hashedPassword, found)
    }

    @Test
    fun `Given no password hash for the user, Then findUserPasswordHash returns null`() {
        // Given
        val user = createAndSaveUser()

        // When
        val found = repository.findUserPasswordHash(user)

        // Then
        assertNull(found)
    }

    @Test
    fun `Given a nonexistent user, Then saveUserPasswordHash throws UserModelDoesNotExistError`() {
        // Given
        val nonexistentUser = User(id = randomUUID(), name = createRandomString())
        val hashedPassword = HashedPassword(hash = "hash", algorithm = PasswordHashAlgorithm.BCRYPT)

        // When, Then
        assertThrows(UserModelDoesNotExistError::class.java) {
            repository.saveUserPasswordHash(nonexistentUser, hashedPassword)
        }
    }
}
