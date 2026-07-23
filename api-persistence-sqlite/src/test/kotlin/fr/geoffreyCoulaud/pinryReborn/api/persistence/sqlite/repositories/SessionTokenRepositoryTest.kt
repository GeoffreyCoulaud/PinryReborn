package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.RepositoryTest
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.exceptions.UserModelDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID.randomUUID

class SessionTokenRepositoryTest : RepositoryTest() {
    private val repository = SessionTokenRepository(database = database)
    private val userRepository = UserRepository(database = database)

    private fun createUser(): User =
        userRepository.saveUser(User(id = randomUUID(), name = createRandomString(), createdAt = storableNow()))

    private fun sessionToken(
        user: User,
        persistent: Boolean = false,
        expiresAt: Instant = storableNow().plusSeconds(3600),
    ) = SessionToken(id = randomUUID(), user = user, expiresAt = expiresAt, persistent = persistent)

    @Test
    fun `Given a saved token, Then findByTokenHash returns it with its user and fields`() {
        // Given
        val user = createUser()
        val expiresAt = storableNow().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS)
        val token = sessionToken(user, persistent = true, expiresAt = expiresAt)

        // When
        repository.saveSessionToken(token, tokenHash = "hash-a")
        val loaded = repository.findByTokenHash("hash-a")

        // Then
        assertEquals(token.id, loaded!!.id)
        assertEquals(user.id, loaded.user.id)
        assertEquals(expiresAt, loaded.expiresAt.truncatedTo(ChronoUnit.MILLIS))
        assertTrue(loaded.persistent)
    }

    @Test
    fun `Given no token for a hash, Then findByTokenHash returns null`() {
        assertNull(repository.findByTokenHash("absent"))
    }

    @Test
    fun `Given a nonexistent user, Then saveSessionToken throws UserModelDoesNotExistError`() {
        // Given
        val nonexistentUser = User(id = randomUUID(), name = createRandomString(), createdAt = storableNow())
        val token = sessionToken(nonexistentUser)

        // When, Then
        assertThrows(UserModelDoesNotExistError::class.java) {
            repository.saveSessionToken(token, tokenHash = "hash-nonexistent-user")
        }
    }

    @Test
    fun `Given a saved token, Then deleteById removes it`() {
        val user = createUser()
        val token = sessionToken(user)
        repository.saveSessionToken(token, tokenHash = "hash-b")
        repository.deleteById(token.id)
        assertNull(repository.findByTokenHash("hash-b"))
    }

    @Test
    fun `Given several tokens for a user, Then deleteAllForUser removes them all`() {
        val user = createUser()
        repository.saveSessionToken(sessionToken(user), tokenHash = "h1")
        repository.saveSessionToken(sessionToken(user), tokenHash = "h2")
        repository.deleteAllForUser(user.id)
        assertNull(repository.findByTokenHash("h1"))
        assertNull(repository.findByTokenHash("h2"))
    }

    @Test
    fun `Given tokens for two users, Then deleteAllForUser only removes the target user's tokens`() {
        val userA = createUser()
        val userB = createUser()
        repository.saveSessionToken(sessionToken(userA), tokenHash = "ha")
        repository.saveSessionToken(sessionToken(userB), tokenHash = "hb")
        repository.deleteAllForUser(userA.id)
        assertNull(repository.findByTokenHash("ha"))
        assertNotNull(repository.findByTokenHash("hb"))
    }
}
