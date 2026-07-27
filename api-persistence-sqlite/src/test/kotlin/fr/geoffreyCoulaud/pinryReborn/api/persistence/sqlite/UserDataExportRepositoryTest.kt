package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.CursorDirection
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportAlreadyInProgressException
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.exceptions.UserModelDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserDataExportRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserRepository
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID

class UserDataExportRepositoryTest : RepositoryTest() {
    private val repository = UserDataExportRepository(database)
    private val userRepository = UserRepository(database)

    private fun createAndSaveUser(): User =
        userRepository.saveUser(User(id = randomUUID(), name = createRandomString(), createdAt = storableNow()))

    private fun pendingExport(
        userId: UUID,
        requestedAt: Instant = Instant.parse("2026-07-22T10:00:00Z"),
    ) = UserDataExport(
        id = randomUUID(),
        userId = userId,
        state = UserDataExportState.PENDING,
        formatVersion = 1,
        requestedAt = requestedAt,
    )

    // --- save / findById ---

    @Test
    fun `Given a new export, Then saving it returns it with the same user`() {
        // Given
        val user = createAndSaveUser()

        // When
        val saved = repository.save(pendingExport(user.id))

        // Then
        assertEquals(user.id, saved.userId)
    }

    @Test
    fun `Given a saved export, Then it is found by id`() {
        // Given
        val user = createAndSaveUser()
        val export = repository.save(pendingExport(user.id))

        // When
        val found = repository.findById(export.id)

        // Then
        assertEquals(export.id, found?.id)
    }

    @Test
    fun `Given an unknown id, Then findById returns null`() {
        // Given / When
        val found = repository.findById(randomUUID())

        // Then
        assertNull(found)
    }

    @Test
    fun `Given an export for a nonexistent user, Then saving it throws UserModelDoesNotExistError`() {
        // Given
        val export = pendingExport(randomUUID())

        // When / Then
        assertThrows(UserModelDoesNotExistError::class.java) { repository.save(export) }
    }

    @Test
    fun `Given an export whose owner is later soft-deleted, Then reading it back does not crash`() {
        // Given
        val user = createAndSaveUser()
        val export = repository.save(pendingExport(user.id).copy(state = UserDataExportState.DELETED))
        userRepository.markPendingDeletion(user)

        // When
        val found = repository.findById(export.id)

        // Then
        assertEquals(user.id, found?.userId)
    }

    // --- pending / ready ---

    @Test
    fun `Given a pending export, Then it is found for its user`() {
        // Given
        val user = createAndSaveUser()
        val export = repository.save(pendingExport(user.id))

        // When
        val found = repository.findPendingForUser(user.id)

        // Then
        assertEquals(export.id, found?.id)
    }

    @Test
    fun `Given only a ready export, Then no pending export is found`() {
        // Given
        val user = createAndSaveUser()
        repository.save(pendingExport(user.id).copy(state = UserDataExportState.READY))

        // When
        val found = repository.findPendingForUser(user.id)

        // Then
        assertNull(found)
    }

    @Test
    fun `Given a ready export, Then it is found for its user`() {
        // Given
        val user = createAndSaveUser()
        val export = repository.save(pendingExport(user.id).copy(state = UserDataExportState.READY))

        // When
        val found = repository.findReadyForUser(user.id)

        // Then
        assertEquals(export.id, found?.id)
    }

    @Test
    fun `Given only a pending export, Then no ready export is found`() {
        // Given
        val user = createAndSaveUser()
        repository.save(pendingExport(user.id))

        // When
        val found = repository.findReadyForUser(user.id)

        // Then
        assertNull(found)
    }

    // --- expiry ---

    @Test
    fun `Given a ready export past its expiry, Then it is listed as expired`() {
        // Given
        val user = createAndSaveUser()
        val export = repository.save(
            pendingExport(user.id).copy(
                state = UserDataExportState.READY,
                expiresAt = Instant.parse("2026-07-22T00:00:00Z"),
            ),
        )
        val now = Instant.parse("2026-07-23T00:00:00Z")

        // When
        val expired = repository.findExpiredReadyExports(now)

        // Then
        assertEquals(listOf(export.id), expired.map { it.id })
    }

    @Test
    fun `Given a ready export before its expiry, Then it is not listed as expired`() {
        // Given
        val user = createAndSaveUser()
        repository.save(
            pendingExport(user.id).copy(
                state = UserDataExportState.READY,
                expiresAt = Instant.parse("2026-07-24T00:00:00Z"),
            ),
        )
        val now = Instant.parse("2026-07-23T00:00:00Z")

        // When
        val expired = repository.findExpiredReadyExports(now)

        // Then
        assertTrue(expired.isEmpty())
    }

    // --- last requested ---

    @Test
    fun `Given a deleted export as the only row, Then it still counts as the last request`() {
        // Given
        val user = createAndSaveUser()
        val at = Instant.parse("2026-07-22T09:00:00Z")
        repository.save(pendingExport(user.id, at).copy(state = UserDataExportState.DELETED))

        // When / Then
        assertEquals(at, repository.findLastRequestedAtForUser(user.id))
    }

    @Test
    fun `Given no exports for a user, Then there is no last requested time`() {
        // Given
        val user = createAndSaveUser()

        // When
        val lastRequestedAt = repository.findLastRequestedAtForUser(user.id)

        // Then
        assertNull(lastRequestedAt)
    }

    // --- unique index ---

    @Test
    fun `Given a second pending export for one user, Then saving it violates the unique index`() {
        // Given
        val user = createAndSaveUser()
        repository.save(pendingExport(user.id))

        // When / Then
        assertThrows(ExportAlreadyInProgressException::class.java) { repository.save(pendingExport(user.id)) }
    }

    // --- ids / delete ---

    @Test
    fun `Given several exports for a user, Then findAllExportIdsForUser returns all their ids`() {
        // Given
        val user = createAndSaveUser()
        val first = repository.save(pendingExport(user.id))
        val second = repository.save(
            pendingExport(user.id, Instant.parse("2026-07-22T11:00:00Z")).copy(state = UserDataExportState.READY),
        )

        // When
        val ids = repository.findAllExportIdsForUser(user.id)

        // Then
        assertEquals(setOf(first.id, second.id), ids.toSet())
    }

    @Test
    fun `Given exports for two users, Then deleteAllForUser removes only that user's rows`() {
        // Given
        val user = createAndSaveUser()
        val otherUser = createAndSaveUser()
        repository.save(pendingExport(user.id))
        val otherExport = repository.save(pendingExport(otherUser.id))

        // When
        repository.deleteAllForUser(user.id)

        // Then
        assertTrue(repository.findAllExportIdsForUser(user.id).isEmpty())
        assertEquals(listOf(otherExport.id), repository.findAllExportIdsForUser(otherUser.id))
    }

    // --- findMissingExportIds (orphan sweep) ---

    @Test
    fun `Given a mix of present and absent candidate ids, Then findMissingExportIds returns only the absent`() {
        // Given
        val user = createAndSaveUser()
        val saved = repository.save(pendingExport(user.id))
        val missingId = randomUUID()

        // When
        val missing = repository.findMissingExportIds(listOf(saved.id, missingId))

        // Then
        assertEquals(setOf(missingId), missing)
    }

    @Test
    fun `Given an empty candidate set, Then findMissingExportIds returns empty`() {
        // Given
        val user = createAndSaveUser()
        repository.save(pendingExport(user.id))

        // When
        val missing = repository.findMissingExportIds(emptyList())

        // Then
        assertTrue(missing.isEmpty())
    }

    // --- paging ---

    @Test
    fun `Given a user with no exports, Then findAllForUser returns an empty page with no cursors`() {
        // Given
        val user = createAndSaveUser()

        // When
        val page = repository.findAllForUser(user.id, cursor = null, pageSize = 2)

        // Then
        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
        assertNull(page.previousCursor)
    }

    @Test
    fun `Given a cursor pointing at a row that no longer exists, Then it is treated as absent`() {
        // Given
        val user = createAndSaveUser()
        repository.save(pendingExport(user.id).copy(state = UserDataExportState.DELETED))
        val staleCursor = Cursor(pivotId = randomUUID(), direction = CursorDirection.FORWARD)

        // When
        val page = repository.findAllForUser(user.id, cursor = staleCursor, pageSize = 2)

        // Then
        assertEquals(1, page.items.size)
    }

    @Test
    fun `Given several pages of exports, Then findAllForUser orders them newest first and pages through all`() {
        // Given
        val user = createAndSaveUser()
        val base = Instant.parse("2026-07-22T10:00:00Z")
        val ids = (0 until 5).map { index ->
            repository.save(
                pendingExport(user.id, base.plusSeconds(index.toLong())).copy(state = UserDataExportState.DELETED),
            ).id
        }
        val newestFirst = ids.reversed()

        // When
        val firstPage = repository.findAllForUser(user.id, cursor = null, pageSize = 2)
        val secondPage = repository.findAllForUser(user.id, cursor = firstPage.nextCursor, pageSize = 2)
        val thirdPage = repository.findAllForUser(user.id, cursor = secondPage.nextCursor, pageSize = 2)
        val backToFirst = repository.findAllForUser(user.id, cursor = secondPage.previousCursor, pageSize = 2)

        // Then
        assertEquals(newestFirst.subList(0, 2), firstPage.items.map { it.id })
        assertEquals(newestFirst.subList(2, 4), secondPage.items.map { it.id })
        assertEquals(newestFirst.subList(4, 5), thirdPage.items.map { it.id })
        assertNull(thirdPage.nextCursor)
        assertEquals(firstPage.items.map { it.id }, backToFirst.items.map { it.id })
    }
}
