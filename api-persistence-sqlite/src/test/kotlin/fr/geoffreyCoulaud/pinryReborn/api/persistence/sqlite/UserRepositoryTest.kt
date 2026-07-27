package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserRepository
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import jakarta.persistence.PersistenceException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.UUID.randomUUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UserRepositoryTest : RepositoryTest() {
    private val repository = UserRepository(database)

    private fun saveUser(name: String = createRandomString()) =
        repository.saveUser(User(id = randomUUID(), name = name, createdAt = storableNow()))

    @Test
    fun `saveUser should persist user and return it with same id`() {
        // Given
        val user = User(id = randomUUID(), name = "Test User", createdAt = storableNow())

        // When
        val savedUser = repository.saveUser(user)

        // Then
        assertEquals(user.id, savedUser.id)
        assertEquals(user.name, savedUser.name)
    }

    @Test
    fun `findUser should return user when exists`() {
        // Given
        val user = User(id = randomUUID(), name = "Findable User", createdAt = storableNow())
        repository.saveUser(user)

        // When
        val foundUser = repository.findUserById(user.id)

        // Then
        assertNotNull(foundUser)
        assertEquals(user.id, foundUser!!.id)
        assertEquals(user.name, foundUser.name)
    }

    @Test
    fun `findUser should return null when user does not exist`() {
        // When
        val foundUser = repository.findUserById(randomUUID())

        // Then
        assertNull(foundUser)
    }

    @Test
    fun `Given a tombstoned user, Then normal lookups hide it but including-deleted finds it`() {
        // Given
        val user = saveUser()
        repository.markPendingDeletion(user)

        // When / Then
        assertNull(repository.findUserById(user.id))
        assertNull(repository.findUserByName(user.name))
        val found = repository.findUserByIdIncludingDeleted(user.id)
        assertEquals(user.id, found?.id)
        assertTrue(found!!.softDeleted)
        assertEquals(user.id, repository.findUserByNameIncludingDeleted(user.name)?.id)
    }

    @Test
    fun `Given a tombstoned user, Then permanentlyDeleteUser removes it entirely`() {
        // Given
        val user = saveUser()
        repository.markPendingDeletion(user)

        // When
        repository.permanentlyDeleteUser(user)

        // Then
        assertNull(repository.findUserByIdIncludingDeleted(user.id))
    }

    @Test
    fun `Given an active user, Then findUserById returns it with softDeleted false`() {
        // Given
        val user = saveUser()

        // When / Then
        assertEquals(false, repository.findUserById(user.id)?.softDeleted)
    }

    @Test
    fun `Given a never-saved user, Then markPendingDeletion is a no-op`() {
        // Given
        val user = User(id = randomUUID(), name = createRandomString(), createdAt = storableNow())

        // When / Then
        repository.markPendingDeletion(user)
    }

    @Test
    fun `Given a never-saved user, Then permanentlyDeleteUser is a no-op`() {
        // Given
        val user = User(id = randomUUID(), name = createRandomString(), createdAt = storableNow())

        // When / Then
        repository.permanentlyDeleteUser(user)
    }

    @Test
    fun `Given no user with the given name, Then findUserByNameIncludingDeleted returns null`() {
        // Given
        // When
        val foundUser = repository.findUserByNameIncludingDeleted(createRandomString())

        // Then
        assertNull(foundUser)
    }

    @Test
    fun `saveUser should update existing user`() {
        // Given
        val originalUser = User(id = randomUUID(), name = "Original Name", createdAt = storableNow())
        repository.saveUser(originalUser)

        // When
        val updatedUser = originalUser.copy(name = "Updated Name")
        repository.saveUser(updatedUser)

        // Then
        val foundUser = repository.findUserById(originalUser.id)
        assertNotNull(foundUser)
        assertEquals("Updated Name", foundUser!!.name)
    }

    @Test
    fun `findUserByName is case-insensitive`() {
        // Given
        val user = User(id = randomUUID(), name = "Bob", createdAt = storableNow())
        repository.saveUser(user)

        // When
        val foundUser = repository.findUserByName("bob")

        // Then
        assertNotNull(foundUser)
        assertEquals("Bob", foundUser!!.name)
    }

    @Test
    fun `saving two users whose names differ only by case is rejected`() {
        // Given
        repository.saveUser(User(id = randomUUID(), name = "Alice", createdAt = storableNow()))

        // When, Then
        assertThrows<PersistenceException> {
            repository.saveUser(User(id = randomUUID(), name = "alice", createdAt = storableNow()))
        }
    }

    @Test
    fun `Given no user with the given name, Then findUserByName returns null`() {
        // Given
        // When
        val foundUser = repository.findUserByName("nobody")

        // Then
        assertNull(foundUser)
    }

    // --- Creation timestamps ---

    @Test
    fun `Given a saved user, Then reading it back exposes its creation timestamp`() {
        // Given
        val user = saveUser()

        // When
        val found = repository.findUserById(user.id)

        // Then
        assertNotNull(found?.createdAt)
    }

    // --- Tombstone sweep ---

    @Test
    fun `Given tombstones and active users, Then findTombstonedUsersModifiedBefore returns only stale tombstones`() {
        // Given: an active user back-dated before the cutoff (excluded: not soft-deleted),
        // a stale tombstone back-dated before the cutoff (returned), and a fresh tombstone set
        // after the cutoff (excluded: too recent). `when_modified` is `@WhenModified`, which Ebean
        // overwrites on every save, so the back-date is a raw SQL update rather than a re-save.
        val cutoff = storableNow()
        val activeUser = saveUser()
        val staleTombstone = saveUser()
        val freshTombstone = saveUser()
        repository.markPendingDeletion(staleTombstone)
        repository.markPendingDeletion(freshTombstone)
        val beforeCutoff = cutoff.minus(2, ChronoUnit.HOURS)
        val afterCutoff = cutoff.plus(2, ChronoUnit.HOURS)
        backDateWhenModified(activeUser.id, beforeCutoff)
        backDateWhenModified(staleTombstone.id, beforeCutoff)
        backDateWhenModified(freshTombstone.id, afterCutoff)

        // When
        val tombstones = repository.findTombstonedUsersModifiedBefore(cutoff)

        // Then: only the stale tombstone is returned; the active user and the fresh tombstone are not
        assertEquals(1, tombstones.size)
        val only = tombstones.single()
        assertEquals(staleTombstone.id, only.id)
        assertTrue(only.softDeleted)
    }

    private fun backDateWhenModified(id: UUID, whenModified: Instant) {
        database
            .sqlUpdate("UPDATE users SET when_modified = ? WHERE id = ?")
            .setParameter(1, whenModified)
            .setParameter(2, id)
            .execute()
    }
}
