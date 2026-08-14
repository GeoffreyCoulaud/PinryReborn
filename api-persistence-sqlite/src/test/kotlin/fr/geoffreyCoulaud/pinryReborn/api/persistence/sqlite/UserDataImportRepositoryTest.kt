package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.CursorDirection
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportAlreadyInProgressException
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.exceptions.UserModelDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserDataImportRepository
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

class UserDataImportRepositoryTest : RepositoryTest() {
    private val repository = UserDataImportRepository(persistor)
    private val userRepository = UserRepository(persistor)
    private val requestedAt = Instant.parse("2026-08-14T10:00:00Z")

    private fun createAndSaveUser(): User =
        userRepository.saveUser(User(id = randomUUID(), name = createRandomString(), createdAt = storableNow()))

    private fun awaitingImport(
        userId: UUID,
        at: Instant = requestedAt,
    ) = UserDataImport(
        id = randomUUID(),
        userId = userId,
        state = UserDataImportState.AWAITING_ARCHIVE,
        requestedAt = at,
    )

    // --- save / findById ---

    @Test
    fun `Given a new import, Then saving it returns it with the same user and its counters`() {
        // Given
        val user = createAndSaveUser()

        // When
        val saved = repository.save(awaitingImport(user.id).copy(announcedPins = 12, processedPins = 3))

        // Then
        assertEquals(user.id, saved.userId)
        assertEquals(12, saved.announcedPins)
        assertEquals(3, saved.processedPins)
    }

    @Test
    fun `Given a saved import, Then it is found by id with every field it was given`() {
        // Given
        val user = createAndSaveUser()
        val runToken = randomUUID()
        val stored =
            repository.save(
                awaitingImport(user.id).copy(
                    state = UserDataImportState.RUNNING,
                    taskId = randomUUID(),
                    runToken = runToken,
                    uploadedBytes = 4096,
                    lastUploadActivityAt = requestedAt.plusSeconds(10),
                    archiveCompletedAt = requestedAt.plusSeconds(20),
                    startedAt = requestedAt.plusSeconds(30),
                    storageKey = "imports/a.zip",
                    byteSize = 4096,
                    formatVersion = 1,
                    issueCount = 2,
                    issueDetailTruncated = true,
                ),
            )

        // When
        val found = repository.findById(stored.id)

        // Then
        assertEquals(stored, found)
        assertEquals(runToken, found?.runToken)
    }

    @Test
    fun `Given an unknown id, Then findById returns null`() {
        // Given / When
        val found = repository.findById(randomUUID())

        // Then
        assertNull(found)
    }

    @Test
    fun `Given an import for a nonexistent user, Then saving it throws UserModelDoesNotExistError`() {
        // Given / When / Then
        assertThrows(UserModelDoesNotExistError::class.java) { repository.save(awaitingImport(randomUUID())) }
    }

    @Test
    fun `Given a tombstoned owner, Then a terminal re-save moves the row without re-resolving the user`() {
        // Given: the sweep marks a row FAILED long after the account cleaner tombstoned its owner
        val user = createAndSaveUser()
        val stored = repository.save(awaitingImport(user.id))
        userRepository.markPendingDeletion(user, storableNow())

        // When
        val reSaved = repository.save(stored.copy(state = UserDataImportState.FAILED, failureCode = "IMPORT_FAILED"))

        // Then
        assertEquals(UserDataImportState.FAILED, reSaved.state)
        assertEquals(user.id, reSaved.userId)
    }

    // --- one active import per user ---

    @Test
    fun `Given an active import, Then a second one for the same user is refused as already in progress`() {
        // Given
        val user = createAndSaveUser()
        repository.save(awaitingImport(user.id))

        // When / Then: the index is the only authority, and the adapter translates what it raises
        assertThrows(ImportAlreadyInProgressException::class.java) { repository.save(awaitingImport(user.id)) }
    }

    @Test
    fun `Given an active import, Then another user may still start one`() {
        // Given
        val user = createAndSaveUser()
        val otherUser = createAndSaveUser()
        repository.save(awaitingImport(user.id))

        // When
        val other = repository.save(awaitingImport(otherUser.id))

        // Then
        assertEquals(otherUser.id, other.userId)
    }

    @Test
    fun `Given a terminal import, Then the same user may start another one`() {
        // Given
        val user = createAndSaveUser()
        val first = repository.save(awaitingImport(user.id))
        repository.save(first.copy(state = UserDataImportState.COMPLETED))

        // When
        val second = repository.save(awaitingImport(user.id, requestedAt.plusSeconds(60)))

        // Then
        assertEquals(user.id, second.userId)
    }

    @Test
    fun `Given a running import, Then its own re-save is not mistaken for a second one`() {
        // Given: the walk re-saves the row on every cursor advance, and each save is still an active state
        val user = createAndSaveUser()
        val stored = repository.save(awaitingImport(user.id))

        // When
        val advanced = repository.save(stored.copy(state = UserDataImportState.RUNNING, processedPins = 7))

        // Then
        assertEquals(7, advanced.processedPins)
    }

    // --- abandonment sweep ---

    @Test
    fun `Given an upload idle since before the grace, Then it is abandonable`() {
        // Given
        val user = createAndSaveUser()
        val stored =
            repository.save(awaitingImport(user.id).copy(lastUploadActivityAt = requestedAt.plusSeconds(60)))

        // When
        val abandonable = repository.findAbandonableBefore(requestedAt.plusSeconds(120))

        // Then
        assertEquals(listOf(stored.id), abandonable.map { it.id })
    }

    @Test
    fun `Given an upload still receiving chunks, Then it is not abandonable`() {
        // Given: the grace counts inactivity, not age, so a long upload still streaming survives it
        val user = createAndSaveUser()
        repository.save(awaitingImport(user.id).copy(lastUploadActivityAt = requestedAt.plusSeconds(300)))

        // When
        val abandonable = repository.findAbandonableBefore(requestedAt.plusSeconds(120))

        // Then
        assertTrue(abandonable.isEmpty())
    }

    @Test
    fun `Given an import that never received a chunk, Then its request time is what the grace counts`() {
        // Given
        val user = createAndSaveUser()
        val stored = repository.save(awaitingImport(user.id))

        // When
        val abandonable = repository.findAbandonableBefore(requestedAt.plusSeconds(1))

        // Then
        assertEquals(listOf(stored.id), abandonable.map { it.id })
    }

    @Test
    fun `Given an import past its upload phase, Then it is never abandonable`() {
        // Given
        val user = createAndSaveUser()
        val stored = repository.save(awaitingImport(user.id))
        repository.save(stored.copy(state = UserDataImportState.PENDING))

        // When
        val abandonable = repository.findAbandonableBefore(requestedAt.plusSeconds(120))

        // Then
        assertTrue(abandonable.isEmpty())
    }

    // --- storage reclamation ---

    @Test
    fun `Given a terminal import still holding an archive, Then it is reclaimable`() {
        // Given
        val user = createAndSaveUser()
        val stored = repository.save(awaitingImport(user.id))
        repository.save(stored.copy(state = UserDataImportState.CANCELLED, storageKey = "imports/${stored.id}.zip"))

        // When
        val reclaimable = repository.findReclaimableTerminal()

        // Then
        assertEquals(listOf(stored.id), reclaimable.map { it.id })
    }

    @Test
    fun `Given a terminal import whose archive is already gone, Then it is not reclaimable`() {
        // Given
        val user = createAndSaveUser()
        val stored = repository.save(awaitingImport(user.id))
        repository.save(stored.copy(state = UserDataImportState.COMPLETED, storageKey = null))

        // When
        val reclaimable = repository.findReclaimableTerminal()

        // Then
        assertTrue(reclaimable.isEmpty())
    }

    @Test
    fun `Given a running import holding an archive, Then it is not reclaimable`() {
        // Given: the walk is still reading those bytes
        val user = createAndSaveUser()
        val stored = repository.save(awaitingImport(user.id))
        repository.save(stored.copy(state = UserDataImportState.RUNNING, storageKey = "imports/${stored.id}.zip"))

        // When
        val reclaimable = repository.findReclaimableTerminal()

        // Then
        assertTrue(reclaimable.isEmpty())
    }

    // --- interrupted runs ---

    @Test
    fun `Given a running import, Then it is the only state findRunning answers with`() {
        // Given: one row per state, so a predicate widened by accident is caught here
        val user = createAndSaveUser()
        val running = repository.save(awaitingImport(user.id).copy(state = UserDataImportState.RUNNING))
        UserDataImportState.entries
            .filterNot { it == UserDataImportState.RUNNING }
            .forEach { state ->
                repository.save(awaitingImport(createAndSaveUser().id).copy(state = state))
            }

        // When
        val found = repository.findRunning()

        // Then
        assertEquals(listOf(running.id), found.map { it.id })
    }

    // --- orphan sweep, ids and deletion ---

    @Test
    fun `Given a mix of present and absent candidate ids, Then findMissingImportIds returns only the absent`() {
        // Given
        val user = createAndSaveUser()
        val stored = repository.save(awaitingImport(user.id))
        val missingId = randomUUID()

        // When
        val missing = repository.findMissingImportIds(listOf(stored.id, missingId))

        // Then
        assertEquals(setOf(missingId), missing)
    }

    @Test
    fun `Given an empty candidate set, Then findMissingImportIds returns empty`() {
        // Given
        val user = createAndSaveUser()
        repository.save(awaitingImport(user.id))

        // When
        val missing = repository.findMissingImportIds(emptyList())

        // Then
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `Given several imports for a user, Then findAllImportIdsForUser returns all their ids`() {
        // Given
        val user = createAndSaveUser()
        val first = repository.save(awaitingImport(user.id))
        repository.save(first.copy(state = UserDataImportState.COMPLETED))
        val second = repository.save(awaitingImport(user.id, requestedAt.plusSeconds(60)))

        // When
        val ids = repository.findAllImportIdsForUser(user.id)

        // Then
        assertEquals(setOf(first.id, second.id), ids.toSet())
    }

    @Test
    fun `Given imports for two users, Then deleteAllForUser removes only that user's rows`() {
        // Given
        val user = createAndSaveUser()
        val otherUser = createAndSaveUser()
        repository.save(awaitingImport(user.id))
        val otherImport = repository.save(awaitingImport(otherUser.id))

        // When
        repository.deleteAllForUser(user.id)

        // Then
        assertTrue(repository.findAllImportIdsForUser(user.id).isEmpty())
        assertEquals(listOf(otherImport.id), repository.findAllImportIdsForUser(otherUser.id))
    }

    // --- paging ---

    @Test
    fun `Given a user with no imports, Then findAllForUser returns an empty page with no cursors`() {
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
        repository.save(awaitingImport(user.id))
        val staleCursor = Cursor(pivotId = randomUUID(), direction = CursorDirection.FORWARD)

        // When
        val page = repository.findAllForUser(user.id, cursor = staleCursor, pageSize = 2)

        // Then
        assertEquals(1, page.items.size)
    }

    @Test
    fun `Given several pages of imports, Then findAllForUser orders them newest first and pages through all`() {
        // Given: terminal rows, since the active slot holds only one at a time
        val user = createAndSaveUser()
        val ids =
            (0 until 5).map { index ->
                val stored = repository.save(awaitingImport(user.id, requestedAt.plusSeconds(index.toLong())))
                repository.save(stored.copy(state = UserDataImportState.COMPLETED)).id
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

    @Test
    fun `Given imports sharing one request instant, Then the cursor still advances through them`() {
        // Given: ordering on requestedAt alone stalls a page boundary inside a group sharing it
        val user = createAndSaveUser()
        val ids =
            (0 until 3).map {
                val stored = repository.save(awaitingImport(user.id))
                repository.save(stored.copy(state = UserDataImportState.COMPLETED)).id
            }

        // When
        val firstPage = repository.findAllForUser(user.id, cursor = null, pageSize = 2)
        val secondPage = repository.findAllForUser(user.id, cursor = firstPage.nextCursor, pageSize = 2)

        // Then
        assertEquals(ids.toSet(), (firstPage.items + secondPage.items).map { it.id }.toSet())
    }
}
