package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.exceptions.UserModelDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserPasswordHashRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserRepository
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class UserPasswordHashRepositoryTest : RepositoryTest() {
    private val users = UserRepository(database = database)
    private val repository = UserPasswordHashRepository(database = database)

    private fun user() = users.saveUser(User(id = randomUUID(), name = createRandomString(), createdAt = storableNow()))

    private fun hash(h: String) = HashedPassword(hash = h, algorithm = PasswordHashAlgorithm.BCRYPT)

    @Test
    fun `Given two saved hashes, Then current is the latest and all returns both`() {
        // Given
        val user = user()
        repository.saveUserPasswordHash(user, hash("old"))
        Thread.sleep(2) // ensure a distinct when_created for deterministic ordering
        repository.saveUserPasswordHash(user, hash("new"))
        // When / Then
        assertEquals("new", repository.findCurrentPasswordHash(user)?.hash)
        assertEquals(setOf("old", "new"), repository.findAllPasswordHashesForUser(user).map { it.hash }.toSet())
    }

    @Test
    fun `Given saved hashes, Then deleteForUser removes them all`() {
        // Given
        val user = user()
        repository.saveUserPasswordHash(user, hash("a"))
        // When
        repository.deleteForUser(user)
        // Then
        assertNull(repository.findCurrentPasswordHash(user))
        assertEquals(emptyList<HashedPassword>(), repository.findAllPasswordHashesForUser(user))
    }

    @Test
    fun `Given a tombstoned user, Then saveUserPasswordHash throws UserModelDoesNotExistError`() {
        // Given: the row survives a tombstone, so the lookup behind this write is the only thing
        // that keeps a deleted account from acquiring a new credential
        val user = user()
        users.markPendingDeletion(user, storableNow())

        // When, Then
        assertThrows(UserModelDoesNotExistError::class.java) {
            repository.saveUserPasswordHash(user, hash("hash"))
        }
    }

    @Test
    fun `Given a nonexistent user, Then saveUserPasswordHash throws UserModelDoesNotExistError`() {
        // Given
        val nonexistentUser = User(id = randomUUID(), name = createRandomString(), createdAt = storableNow())
        val hashedPassword = hash("hash")

        // When, Then
        assertThrows(UserModelDoesNotExistError::class.java) {
            repository.saveUserPasswordHash(nonexistentUser, hashedPassword)
        }
    }
}
