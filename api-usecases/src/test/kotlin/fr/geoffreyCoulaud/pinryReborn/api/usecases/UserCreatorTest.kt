package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UsernameAlreadyTakenError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.util.UUID.randomUUID

class UserCreatorTest : BaseTest() {
    private val userRepository = mockk<UserRepositoryInterface>()
    private val userPasswordRepository = mockk<UserPasswordHashRepositoryInterface>()
    private val passwordHasher = mockk<PasswordHasher>()
    private val useCase =
        UserCreator(
            userRepository = userRepository,
            userPasswordRepository = userPasswordRepository,
            passwordHasher = passwordHasher,
        )

    @Test
    fun `When creating a user, then should succeed`() {
        // Given
        val name = "John Doe"
        every { userRepository.findUserByNameIncludingDeleted(any()) } returns null
        every { userRepository.saveUser(any()) } answers { firstArg() }

        // When
        // Then
        assertDoesNotThrow {
            useCase.createUser(name)
        }
    }

    @Test
    fun `When creating a user with an already used name, then should throw`() {
        // Given
        val name = "John Doe"
        every { userRepository.findUserByNameIncludingDeleted(name) } returns mockk(relaxed = true, name = name)

        // When,Then
        assertThrows<UsernameAlreadyTakenError> {
            useCase.createUser(name)
        }
    }

    @Test
    fun `Given a name held by a tombstoned user, Then creation is rejected`() {
        // Given
        val name = createRandomString()
        every { userRepository.findUserByNameIncludingDeleted(name) } returns
            User(id = randomUUID(), name = name, softDeleted = true)
        // When / Then
        assertThrows<UsernameAlreadyTakenError> { useCase.createUser(name) }
    }

    @Test
    fun `When creating a user whose name differs only by case, then should throw`() {
        // Given
        every { userRepository.findUserByNameIncludingDeleted(any()) } returns mockk(relaxed = true)

        // When, Then
        assertThrows<UsernameAlreadyTakenError> {
            useCase.createUser("bob")
        }
    }

    @Test
    fun `When creating a user with password, then should succeed`() {
        // Given
        val name = "John Doe"
        val password = createRandomString()
        every { userRepository.findUserByNameIncludingDeleted(any()) } returns null
        every { userRepository.saveUser(any()) } answers { firstArg() }
        every { passwordHasher.hash(any()) } returns HashedPassword("h", PasswordHashAlgorithm.BCRYPT)
        every { userPasswordRepository.saveUserPasswordHash(any(), any()) } answers { secondArg() }

        // When, then
        assertDoesNotThrow {
            useCase.createUserWithPassword(name = name, password = password)
        }
    }
}
