package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UsernameAlreadyTakenError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID.randomUUID

class UserCreatorTest : BaseTest() {
    private val userRepository = mockk<UserRepositoryInterface>()
    private val userPasswordRepository = mockk<UserPasswordHashRepositoryInterface>()
    private val passwordHasher = mockk<PasswordHasher>()
    private val transactionRunner = mockk<TransactionRunner>()
    private val clock = mockk<Clock>()
    private val clockInstant = Instant.parse("2026-07-23T10:00:00Z")
    private val useCase =
        UserCreator(
            userRepository = userRepository,
            userPasswordRepository = userPasswordRepository,
            passwordHasher = passwordHasher,
            transactionRunner = transactionRunner,
            clock = clock,
        )

    // Runs after BaseTest's beforeEachClearMocks(), so the passthrough stub survives into each test
    @BeforeEach
    fun stubTransactionRunnerPassthrough() {
        every { transactionRunner.inTransaction<User>(any()) } answers { firstArg<() -> User>().invoke() }
    }

    @Test
    fun `When creating a user, then should succeed`() {
        // Given
        val name = "John Doe"
        every { clock.now() } returns clockInstant
        every { userRepository.findUserByNameIncludingDeleted(any()) } returns null
        every { userRepository.saveUser(any()) } answers { firstArg() }

        // When
        val created = useCase.createUser(name)

        // Then
        assertEquals(name, created.name)
        assertEquals(clockInstant, created.createdAt)
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
            User(id = randomUUID(), name = name, softDeletedAt = TestTime.now, createdAt = TestTime.now)
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
    fun `Given a clock, Then createUserWithPassword stamps the hash with the clock's instant`() {
        // Given
        val name = "John Doe"
        val password = createRandomString()
        every { clock.now() } returns clockInstant
        every { userRepository.findUserByNameIncludingDeleted(any()) } returns null
        every { userRepository.saveUser(any()) } answers { firstArg() }
        val stamped = HashedPassword("h", PasswordHashAlgorithm.BCRYPT, createdAt = clockInstant)
        every { passwordHasher.hash(password, clockInstant) } returns stamped
        val saved = slot<HashedPassword>()
        every { userPasswordRepository.saveUserPasswordHash(any(), capture(saved)) } returns stamped

        // When
        useCase.createUserWithPassword(name = name, password = password)

        // Then the hash handed to the repository carries the instant from the injected Clock
        assertEquals(clockInstant, saved.captured.createdAt)
    }
}
