package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Login.BasicAuthLogin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.TooManyAuthenticationAttemptsError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UserAuthenticationInvalidPasswordError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UserAuthenticationUserDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.UUID

class UserAuthenticatorTest : BaseTest() {
    private val userRepository = mockk<UserRepositoryInterface>()
    private val userPasswordRepository = mockk<UserPasswordHashRepositoryInterface>()
    private val passwordHasher = mockk<PasswordHasher>()
    private val limiterClock = mockk<Clock>()
    private val threshold = 3

    // Built lazily, from the test body: BaseTest clears every stub after the instance is constructed.
    private val limiter by lazy {
        every { limiterClock.now() } returns TestTime.now
        AuthenticationAttemptLimiter(
            clock = limiterClock,
            threshold = threshold,
            backoffSteps = listOf(Duration.ofSeconds(30)),
            forgetAfter = Duration.ofMinutes(15),
            maxTrackedKeys = 100,
        )
    }
    private val useCase by lazy {
        UserAuthenticator(
            userRepository = userRepository,
            userPasswordRepository = userPasswordRepository,
            passwordHasher = passwordHasher,
            attemptLimiter = limiter,
        )
    }

    private val user = User(id = UUID.randomUUID(), name = createRandomString(), createdAt = TestTime.now)
    private val storedHash =
        HashedPassword(hash = createRandomString(), algorithm = PasswordHashAlgorithm.BCRYPT, createdAt = TestTime.now)
    private val dummyHash =
        HashedPassword(hash = "dummy", algorithm = PasswordHashAlgorithm.BCRYPT, createdAt = TestTime.now)

    private fun givenAKnownUserRefusingEveryPassword() {
        every { userRepository.findUserByName(any()) } returns user
        every { userPasswordRepository.findCurrentPasswordHash(any()) } returns storedHash
        every { passwordHasher.matches(any(), any()) } returns false
    }

    private fun failTimes(login: BasicAuthLogin, count: Int) =
        repeat(count) { assertThrows<UserAuthenticationInvalidPasswordError> { useCase.authenticate(login) } }

    @Test
    fun `When authenticating with basic auth, then should work`() {
        // Given
        val password = createRandomString()
        val login = BasicAuthLogin(user.name, password)
        every { userRepository.findUserByName(any()) } returns user
        every { userPasswordRepository.findCurrentPasswordHash((any())) } returns storedHash
        every { passwordHasher.matches(any(), any()) } returns true

        // When
        val actual = useCase.authenticate(login)

        // Then
        assertEquals(user, actual)
    }

    @Test
    fun `When authenticating with basic auth and no saved password, then should throw`() {
        // Given
        val password = createRandomString()
        val login = BasicAuthLogin(user.name, password)
        every { userRepository.findUserByName(any()) } returns user
        every { userPasswordRepository.findCurrentPasswordHash((any())) } returns null
        every { passwordHasher.hash(any(), any()) } returns dummyHash
        every { passwordHasher.matches(any(), any()) } returns false

        // When, Then
        assertThrows<UserAuthenticationInvalidPasswordError> {
            useCase.authenticate(login)
        }
    }

    @Test
    fun `When authenticating with basic auth with a bad username, then should throw`() {
        // Given
        val login = BasicAuthLogin(user.name, createRandomString())
        every { userRepository.findUserByName(any()) } returns null
        every { passwordHasher.hash(any(), any()) } returns dummyHash
        every { passwordHasher.matches(any(), any()) } returns false

        // When, Then
        assertThrows<UserAuthenticationUserDoesNotExistError> {
            useCase.authenticate(login)
        }
    }

    @Test
    fun `When authenticating with basic auth with a bad password, then should throw`() {
        // Given
        val login = BasicAuthLogin(user.name, createRandomString())
        every { userRepository.findUserByName(any()) } returns user
        every { userPasswordRepository.findCurrentPasswordHash((any())) } returns storedHash
        every { passwordHasher.matches(any(), any()) } returns false

        // When, Then
        assertThrows<UserAuthenticationInvalidPasswordError> {
            useCase.authenticate(login)
        }
    }

    @Test
    fun `Given the threshold reached on wrong passwords, Then the next attempt is refused`() {
        // Given
        val login = BasicAuthLogin(user.name, createRandomString())
        givenAKnownUserRefusingEveryPassword()
        failTimes(login, threshold)

        // When
        val error = assertThrows<TooManyAuthenticationAttemptsError> { useCase.authenticate(login) }

        // Then
        assertEquals(30, error.retryAfterSeconds)
    }

    @Test
    fun `Given the threshold reached on a name with no user, Then the next attempt is refused`() {
        // Given: a name nobody owns, refused as many times as the threshold
        val login = BasicAuthLogin(createRandomString(), createRandomString())
        every { userRepository.findUserByName(any()) } returns null
        every { passwordHasher.hash(any(), any()) } returns dummyHash
        every { passwordHasher.matches(any(), any()) } returns false
        repeat(threshold) { assertThrows<UserAuthenticationUserDoesNotExistError> { useCase.authenticate(login) } }

        // When, Then: counting only existing names would answer 429 for them and 401 for the rest,
        // the enumeration oracle the dummy hash exists to deny (spec D2)
        assertThrows<TooManyAuthenticationAttemptsError> { useCase.authenticate(login) }
    }

    @Test
    fun `Given two logins differing only in case, Then they reach the threshold together`() {
        // Given: the counter is keyed by the submitted name, which the store matches
        // case-insensitively, so a case-sensitive counter would be bypassed (spec D3)
        givenAKnownUserRefusingEveryPassword()
        failTimes(BasicAuthLogin("alice", createRandomString()), threshold - 1)
        failTimes(BasicAuthLogin("aLICE", createRandomString()), 1)

        // When, Then
        assertThrows<TooManyAuthenticationAttemptsError> {
            useCase.authenticate(BasicAuthLogin("Alice", createRandomString()))
        }
    }

    @Test
    fun `Given a blocked name, Then no password reaches the hasher`() {
        // Given
        val login = BasicAuthLogin(user.name, createRandomString())
        givenAKnownUserRefusingEveryPassword()
        failTimes(login, threshold)

        // When
        assertThrows<TooManyAuthenticationAttemptsError> { useCase.authenticate(login) }

        // Then: one hash per attempt up to the block, none for the refused one. A check placed
        // after the verification would leave the CPU exhaustion path open (spec D6).
        verify(exactly = threshold) { passwordHasher.matches(any(), any()) }
    }

    @Test
    fun `Given a success before the threshold, Then the failures before it are dropped`() {
        // Given: one failure short of the threshold
        val login = BasicAuthLogin(user.name, createRandomString())
        givenAKnownUserRefusingEveryPassword()
        failTimes(login, threshold - 1)

        // When: the right password, then as many failures again
        every { passwordHasher.matches(any(), any()) } returns true
        useCase.authenticate(login)
        every { passwordHasher.matches(any(), any()) } returns false

        // Then: the count restarted at the success, so the threshold is out of reach
        failTimes(login, threshold - 1)
    }
}
