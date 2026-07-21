package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Login.BasicAuthLogin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UserAuthenticationInvalidPasswordError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UserAuthenticationUserDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class UserAuthenticatorTest : BaseTest() {
    private val userRepository = mockk<UserRepositoryInterface>()
    private val userPasswordRepository = mockk<UserPasswordHashRepositoryInterface>()
    private val passwordHasher = mockk<PasswordHasher>()
    private val useCase = UserAuthenticator(
        userRepository = userRepository,
        userPasswordRepository = userPasswordRepository,
        passwordHasher = passwordHasher,
    )

    @Test
    fun `When authenticating with basic auth, then should work`() {
        // Given
        val user = User(id = UUID.randomUUID(), name = createRandomString())
        val password = createRandomString()
        val hashedPassword = HashedPassword(hash = createRandomString(), algorithm = PasswordHashAlgorithm.BCRYPT)
        val login = BasicAuthLogin(user.name, password)
        every { userRepository.findUserByName(any()) } returns user
        every { userPasswordRepository.findCurrentPasswordHash((any())) } returns hashedPassword
        every { passwordHasher.matches(any(), any()) } returns true

        // When
        val actual = useCase.authenticate(login)

        // Then
        assertEquals(user, actual)
    }

    @Test
    fun `When authenticating with basic auth and no saved password, then should throw`() {
        // Given
        val user = User(id = UUID.randomUUID(), name = createRandomString())
        val password = createRandomString()
        val login = BasicAuthLogin(user.name, password)
        every { userRepository.findUserByName(any()) } returns user
        every { userPasswordRepository.findCurrentPasswordHash((any())) } returns null
        every { passwordHasher.hash(any()) } returns HashedPassword("dummy", PasswordHashAlgorithm.BCRYPT)
        every { passwordHasher.matches(any(), any()) } returns false

        // When, Then
        assertThrows<UserAuthenticationInvalidPasswordError> {
            useCase.authenticate(login)
        }
    }

    @Test
    fun `When authenticating with basic auth with a bad username, then should throw`() {
        // Given
        val user = User(id = UUID.randomUUID(), name = createRandomString())
        val login = BasicAuthLogin(user.name, createRandomString())
        every { userRepository.findUserByName(any()) } returns null
        every { passwordHasher.hash(any()) } returns HashedPassword("dummy", PasswordHashAlgorithm.BCRYPT)
        every { passwordHasher.matches(any(), any()) } returns false

        // When, Then
        assertThrows<UserAuthenticationUserDoesNotExistError> {
            useCase.authenticate(login)
        }
    }

    @Test
    fun `When authenticating with basic auth with a bad password, then should throw`() {
        // Given
        val user = User(id = UUID.randomUUID(), name = createRandomString())
        val login = BasicAuthLogin(user.name, createRandomString())
        val hashedPassword = HashedPassword(hash = createRandomString(), algorithm = PasswordHashAlgorithm.BCRYPT)
        every { userRepository.findUserByName(any()) } returns user
        every { userPasswordRepository.findCurrentPasswordHash((any())) } returns hashedPassword
        every { passwordHasher.matches(any(), any()) } returns false

        // When, Then
        assertThrows<UserAuthenticationInvalidPasswordError> {
            useCase.authenticate(login)
        }
    }
}
