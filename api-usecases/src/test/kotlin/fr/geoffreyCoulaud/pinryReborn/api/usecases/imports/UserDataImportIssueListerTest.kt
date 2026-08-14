package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImportIssue
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.CursorDirection
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportIssueKind
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportIssueRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportPermissionError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID

/** The report is read through the same ownership check as the import itself, never around it. */
class UserDataImportIssueListerTest : BaseTest() {
    private val repository = mockk<UserDataImportRepositoryInterface>()
    private val issueRepository = mockk<UserDataImportIssueRepositoryInterface>()
    private val lister = UserDataImportIssueLister(UserDataImportGetter(repository), issueRepository)
    private val user = User(id = randomUUID(), name = "alice", createdAt = TestTime.now)
    private val importId = randomUUID()
    private val now = Instant.parse("2026-08-14T10:00:00Z")
    private val cursor = Cursor(pivotId = randomUUID(), direction = CursorDirection.FORWARD)

    private fun importFor(userId: UUID) =
        UserDataImport(
            id = importId,
            userId = userId,
            state = UserDataImportState.COMPLETED,
            requestedAt = now,
        )

    @Test
    fun `Given an unknown import, Then its report is refused as absent`() {
        // Given
        every { repository.findById(importId) } returns null

        // When / Then
        assertThrows(ImportDoesNotExistError::class.java) { lister.list(user, importId, cursor, 20) }
        verify(exactly = 0) { issueRepository.findAllForImport(any(), any(), any()) }
    }

    @Test
    fun `Given another user's import, Then its report is refused`() {
        // Given
        every { repository.findById(importId) } returns importFor(userId = randomUUID())

        // When / Then
        assertThrows(ImportPermissionError::class.java) { lister.list(user, importId, cursor, 20) }
        verify(exactly = 0) { issueRepository.findAllForImport(any(), any(), any()) }
    }

    @Test
    fun `Given the owner's import, Then the issue page is returned as-is`() {
        // Given
        every { repository.findById(importId) } returns importFor(userId = user.id)
        val issue =
            UserDataImportIssue(
                id = randomUUID(),
                importId = importId,
                kind = UserDataImportIssueKind.PIN_HAS_NO_MEDIA,
                line = 12,
                subject = "pins.jsonl",
                detail = null,
            )
        val page = Page(items = listOf(issue), previousCursor = null, nextCursor = null)
        every { issueRepository.findAllForImport(importId, cursor, 20) } returns page

        // When
        val result = lister.list(user, importId, cursor, 20)

        // Then
        assertEquals(page, result)
    }
}
