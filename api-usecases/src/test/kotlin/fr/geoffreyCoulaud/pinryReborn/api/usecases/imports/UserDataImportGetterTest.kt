package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.CursorDirection
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportPermissionError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID

class UserDataImportGetterTest : BaseTest() {
    private val repository = mockk<UserDataImportRepositoryInterface>()
    private val getter = UserDataImportGetter(repository)
    private val user = User(id = randomUUID(), name = "alice", createdAt = TestTime.now)
    private val now = Instant.parse("2026-08-14T10:00:00Z")

    private fun importFor(userId: UUID) =
        UserDataImport(
            id = randomUUID(),
            userId = userId,
            state = UserDataImportState.RUNNING,
            requestedAt = now,
        )

    @Test
    fun `Given an unknown id, Then reading it is refused as absent`() {
        // Given
        val importId = randomUUID()
        every { repository.findById(importId) } returns null

        // When / Then
        assertThrows(ImportDoesNotExistError::class.java) { getter.get(user, importId) }
    }

    @Test
    fun `Given another user's import, Then reading it is refused`() {
        // Given
        val userDataImport = importFor(userId = randomUUID())
        every { repository.findById(userDataImport.id) } returns userDataImport

        // When / Then
        assertThrows(ImportPermissionError::class.java) { getter.get(user, userDataImport.id) }
    }

    @Test
    fun `Given the owner's import, Then it is returned`() {
        // Given
        val userDataImport = importFor(userId = user.id)
        every { repository.findById(userDataImport.id) } returns userDataImport

        // When
        val result = getter.get(user, userDataImport.id)

        // Then
        assertEquals(userDataImport, result)
    }

    @Test
    fun `Given a user's import history, Then the repository page is returned as-is`() {
        // Given
        val cursor = Cursor(pivotId = randomUUID(), direction = CursorDirection.FORWARD)
        val page = Page(items = listOf(importFor(userId = user.id)), previousCursor = null, nextCursor = null)
        every { repository.findAllForUser(user.id, cursor, 20) } returns page

        // When
        val result = getter.list(user, cursor, 20)

        // Then
        assertEquals(page, result)
    }
}
