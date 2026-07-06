package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Login.BasicAuthLogin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UserAuthenticationInvalidPasswordError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UserAuthenticationUserDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID

class UserAuthenticatorTest {
    private val userRepository = mockk<UserRepositoryInterface>()
    private val userPasswordRepository = mockk<UserPasswordHashRepositoryInterface>()
    private val useCase = UserAuthenticator(
        userRepository = userRepository,
        userPasswordRepository = userPasswordRepository,
    )

    @Test
    fun `When authenticating with basic auth, then should work`() {
        // Given
        val user = User(id = UUID.randomUUID(), name = createRandomString())
        val password = createRandomString()
        val hashedPassword = HashedPassword(
            hash = BCrypt.hashpw(password, BCrypt.gensalt()),
            algorithm = PasswordHashAlgorithm.BCRYPT,
        )
        val login = BasicAuthLogin(user.name, password)
        every { userRepository.findUserByName(any()) } returns user
        every { userPasswordRepository.findUserPasswordHash((any())) } returns hashedPassword

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
        every { userPasswordRepository.findUserPasswordHash((any())) } returns null

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
        val hash = BCrypt.hashpw(createRandomString(), BCrypt.gensalt())
        every { userRepository.findUserByName(any()) } returns user
        every { userPasswordRepository.findUserPasswordHash((any())) } returns
                HashedPassword(hash = hash, algorithm = PasswordHashAlgorithm.BCRYPT)

        // When, Then
        assertThrows<UserAuthenticationInvalidPasswordError> {
            useCase.authenticate(login)
        }
    }
}
