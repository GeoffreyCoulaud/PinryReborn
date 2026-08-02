package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.CancelTask
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID
import org.junit.jupiter.api.Test

class UserDataExportDeleterTest : BaseTest() {
    private val getter = mockk<UserDataExportGetter>()
    private val repository = mockk<UserDataExportRepositoryInterface>()
    private val archiveStore = mockk<ExportArchiveStore>()
    private val cancelTask = mockk<CancelTask>()
    private val deleter = UserDataExportDeleter(getter, repository, archiveStore, cancelTask)
    private val user = User(id = randomUUID(), name = "alice", createdAt = TestTime.now)
    private val exportId = randomUUID()
    private val now = Instant.parse("2026-07-22T10:00:00Z")

    private fun exportWith(
        state: UserDataExportState,
        taskId: UUID? = null,
        storageKey: String? = null,
    ) = UserDataExport(
        id = exportId, userId = user.id, state = state, formatVersion = 1, requestedAt = now,
        taskId = taskId, storageKey = storageKey,
    )

    @Test
    fun `Given a pending export, Then deleting it cancels the task and marks it DELETED`() {
        // Given
        val taskId = randomUUID()
        every { getter.get(user, exportId) } returns exportWith(state = UserDataExportState.PENDING, taskId = taskId)
        every { cancelTask.cancel(taskId) } returns true
        every { repository.save(any()) } answers { firstArg() }

        // When
        deleter.delete(user, exportId)

        // Then
        verify { cancelTask.cancel(taskId) }
        verify { repository.save(match { it.id == exportId && it.state == UserDataExportState.DELETED }) }
    }

    @Test
    fun `Given a pending export with no task id, Then no cancellation is attempted`() {
        // Given
        every { getter.get(user, exportId) } returns exportWith(state = UserDataExportState.PENDING, taskId = null)
        every { repository.save(any()) } answers { firstArg() }

        // When
        deleter.delete(user, exportId)

        // Then
        verify(exactly = 0) { cancelTask.cancel(any()) }
        verify { repository.save(match { it.id == exportId && it.state == UserDataExportState.DELETED }) }
    }

    @Test
    fun `Given a ready export, Then deleting it removes the bytes and marks it DELETED`() {
        // Given
        every { getter.get(user, exportId) } returns
            exportWith(state = UserDataExportState.READY, storageKey = "exports/e1.zip")
        every { archiveStore.delete("exports/e1.zip") } just runs
        every { repository.save(any()) } answers { firstArg() }

        // When
        deleter.delete(user, exportId)

        // Then
        verify { archiveStore.delete("exports/e1.zip") }
        verify { repository.save(match { it.id == exportId && it.state == UserDataExportState.DELETED }) }
    }

    @Test
    fun `Given an already terminal export, Then deleting it is a no-op`() {
        // Given
        every { getter.get(user, exportId) } returns exportWith(state = UserDataExportState.DELETED)

        // When
        deleter.delete(user, exportId)

        // Then
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { archiveStore.delete(any()) }
        verify(exactly = 0) { cancelTask.cancel(any()) }
    }
}
