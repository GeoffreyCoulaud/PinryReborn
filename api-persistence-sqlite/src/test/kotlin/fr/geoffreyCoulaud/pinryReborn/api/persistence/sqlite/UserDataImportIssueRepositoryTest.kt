package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImportIssue
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.CursorDirection
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportIssueKind
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserDataImportIssueRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserDataImportRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserRepository
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID

class UserDataImportIssueRepositoryTest : RepositoryTest() {
    private val repository = UserDataImportIssueRepository(persistor)
    private val importRepository = UserDataImportRepository(persistor)
    private val userRepository = UserRepository(persistor)
    private val requestedAt = Instant.parse("2026-08-14T10:00:00Z")

    private fun createAndSaveUser(): User =
        userRepository.saveUser(User(id = randomUUID(), name = createRandomString(), createdAt = storableNow()))

    private fun createAndSaveImport(userId: UUID): UserDataImport =
        importRepository.save(
            UserDataImport(
                id = randomUUID(),
                userId = userId,
                state = UserDataImportState.RUNNING,
                requestedAt = requestedAt,
            ),
        )

    private fun issue(
        importId: UUID,
        kind: UserDataImportIssueKind = UserDataImportIssueKind.LINE_MALFORMED,
        line: Int? = 1,
    ) = UserDataImportIssue(
        id = randomUUID(),
        importId = importId,
        kind = kind,
        line = line,
        subject = "pins.jsonl",
        detail = "Unexpected end of input",
    )

    @Test
    fun `Given a saved issue, Then it comes back with every field it was given`() {
        // Given
        val user = createAndSaveUser()
        val userDataImport = createAndSaveImport(user.id)
        val saved = repository.save(issue(userDataImport.id, UserDataImportIssueKind.MEDIA_TOO_LARGE, line = 42))

        // When
        val page = repository.findAllForImport(userDataImport.id, cursor = null, pageSize = 10)

        // Then
        assertEquals(listOf(saved), page.items)
    }

    @Test
    fun `Given an issue carrying no line, Then it is stored and read back without one`() {
        // Given: an archive-level issue has no line to point at
        val user = createAndSaveUser()
        val userDataImport = createAndSaveImport(user.id)
        val saved =
            repository.save(
                issue(userDataImport.id, line = null).copy(subject = null, detail = null),
            )

        // When
        val page = repository.findAllForImport(userDataImport.id, cursor = null, pageSize = 10)

        // Then
        assertEquals(listOf(saved), page.items)
        assertNull(page.items.single().line)
    }

    @Test
    fun `Given issues on two imports, Then each import lists and counts only its own`() {
        // Given
        val user = createAndSaveUser()
        val otherUser = createAndSaveUser()
        val userDataImport = createAndSaveImport(user.id)
        val otherImport = createAndSaveImport(otherUser.id)
        repository.save(issue(userDataImport.id))
        repository.save(issue(userDataImport.id))
        repository.save(issue(otherImport.id))

        // When
        val page = repository.findAllForImport(userDataImport.id, cursor = null, pageSize = 10)

        // Then
        assertEquals(2, page.items.size)
        assertEquals(2, repository.countForImport(userDataImport.id))
        assertEquals(1, repository.countForImport(otherImport.id))
    }

    @Test
    fun `Given issues belonging to two accounts, Then deleteAllForUser removes only that account's rows`() {
        // Given: account deletion reaps issues before the import rows they hang off
        val user = createAndSaveUser()
        val otherUser = createAndSaveUser()
        val userDataImport = createAndSaveImport(user.id)
        val otherImport = createAndSaveImport(otherUser.id)
        repository.save(issue(userDataImport.id))
        repository.save(issue(otherImport.id))

        // When
        repository.deleteAllForUser(user.id)

        // Then
        assertEquals(0, repository.countForImport(userDataImport.id))
        assertEquals(1, repository.countForImport(otherImport.id))
    }

    @Test
    fun `Given an import with no issue, Then findAllForImport returns an empty page with no cursors`() {
        // Given
        val user = createAndSaveUser()
        val userDataImport = createAndSaveImport(user.id)

        // When
        val page = repository.findAllForImport(userDataImport.id, cursor = null, pageSize = 2)

        // Then
        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
        assertNull(page.previousCursor)
    }

    @Test
    fun `Given a cursor pointing at a row that no longer exists, Then it is treated as absent`() {
        // Given
        val user = createAndSaveUser()
        val userDataImport = createAndSaveImport(user.id)
        repository.save(issue(userDataImport.id))
        val staleCursor = Cursor(pivotId = randomUUID(), direction = CursorDirection.FORWARD)

        // When
        val page = repository.findAllForImport(userDataImport.id, cursor = staleCursor, pageSize = 2)

        // Then
        assertEquals(1, page.items.size)
    }

    @Test
    fun `Given several pages of issues, Then findAllForImport pages through all of them and back`() {
        // Given
        val user = createAndSaveUser()
        val userDataImport = createAndSaveImport(user.id)
        val ids = (0 until 5).map { repository.save(issue(userDataImport.id, line = it)).id }

        // When
        val firstPage = repository.findAllForImport(userDataImport.id, cursor = null, pageSize = 2)
        val secondPage = repository.findAllForImport(userDataImport.id, cursor = firstPage.nextCursor, pageSize = 2)
        val thirdPage = repository.findAllForImport(userDataImport.id, cursor = secondPage.nextCursor, pageSize = 2)
        val backToFirst =
            repository.findAllForImport(userDataImport.id, cursor = secondPage.previousCursor, pageSize = 2)

        // Then
        assertEquals(ids.toSet(), (firstPage.items + secondPage.items + thirdPage.items).map { it.id }.toSet())
        assertNull(thirdPage.nextCursor)
        assertEquals(firstPage.items.map { it.id }, backToFirst.items.map { it.id })
    }
}
