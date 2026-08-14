package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportPermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.CancelTask
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID

class UserDataImportCancellerTest : BaseTest() {
    private val repository = mockk<UserDataImportRepositoryInterface>()
    private val archiveStore = mockk<ImportArchiveStore>()

    // Left unstubbed on purpose: a cancellation this use case must not attempt blows the test up on
    // the call itself, before any `verify` gets to be forgotten.
    private val cancelTask = mockk<CancelTask>()
    private val canceller =
        UserDataImportCanceller(UserDataImportGetter(repository), repository, archiveStore, cancelTask)
    private val user = User(id = randomUUID(), name = "alice", createdAt = TestTime.now)
    private val importId = randomUUID()
    private val now = Instant.parse("2026-08-14T10:00:00Z")
    private val storageKey = "imports/$importId.zip"

    private fun importWith(
        state: UserDataImportState,
        userId: UUID = user.id,
        taskId: UUID? = null,
    ) = UserDataImport(
        id = importId,
        userId = userId,
        state = state,
        requestedAt = now,
        taskId = taskId,
        storageKey = if (state == UserDataImportState.AWAITING_ARCHIVE) null else storageKey,
    )

    private fun verifyCancelled() =
        verify {
            repository.save(match { it.id == importId && it.state == UserDataImportState.CANCELLED })
        }

    @Test
    fun `Given an unknown import, Then cancelling it is refused as absent`() {
        // Given
        every { repository.findById(importId) } returns null

        // When / Then
        assertThrows(ImportDoesNotExistError::class.java) { canceller.cancel(user, importId) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `Given another user's import, Then cancelling it is refused`() {
        // Given
        every { repository.findById(importId) } returns
            importWith(state = UserDataImportState.RUNNING, userId = randomUUID())

        // When / Then
        assertThrows(ImportPermissionError::class.java) { canceller.cancel(user, importId) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `Given an import still awaiting its archive, Then the partial upload goes and no task is cancelled`() {
        // Given: no task exists yet at this point, so there is nothing to cancel
        every { repository.findById(importId) } returns importWith(state = UserDataImportState.AWAITING_ARCHIVE)
        every { archiveStore.discardPartialUpload(importId) } just runs
        every { repository.save(any()) } answers { firstArg() }

        // When
        canceller.cancel(user, importId)

        // Then
        verify { archiveStore.discardPartialUpload(importId) }
        verify(exactly = 0) { archiveStore.delete(any()) }
        verify(exactly = 0) { cancelTask.cancel(any()) }
        verifyCancelled()
    }

    @Test
    fun `Given a pending import, Then its task is cancelled and its archive deleted`() {
        // Given: the only state that does both, since the archive is promoted and no runner holds it
        val taskId = randomUUID()
        every { repository.findById(importId) } returns
            importWith(state = UserDataImportState.PENDING, taskId = taskId)
        every { cancelTask.cancel(taskId) } returns true
        every { archiveStore.delete(storageKey) } just runs
        every { repository.save(any()) } answers { firstArg() }

        // When
        canceller.cancel(user, importId)

        // Then
        verify { cancelTask.cancel(taskId) }
        verify { archiveStore.delete(storageKey) }
        verifyCancelled()
    }

    @Test
    fun `Given a pending import whose key column was never written, Then the derived key is still deleted`() {
        // Given: the fixture above stores exactly what the key derives, so it cannot tell one from the
        // other; with the column null, only a derived key names those bytes.
        val taskId = randomUUID()
        every { repository.findById(importId) } returns
            importWith(state = UserDataImportState.PENDING, taskId = taskId).copy(storageKey = null)
        every { cancelTask.cancel(taskId) } returns true
        every { archiveStore.delete(storageKey) } just runs
        every { repository.save(any()) } answers { firstArg() }

        // When
        canceller.cancel(user, importId)

        // Then
        verify { archiveStore.delete(storageKey) }
        verifyCancelled()
    }

    @Test
    fun `Given a pending import with no task id, Then no cancellation is attempted and the archive still goes`() {
        // Given: a completer that died between the PENDING write and the task id write leaves this row
        every { repository.findById(importId) } returns
            importWith(state = UserDataImportState.PENDING, taskId = null)
        every { archiveStore.delete(storageKey) } just runs
        every { repository.save(any()) } answers { firstArg() }

        // When
        canceller.cancel(user, importId)

        // Then
        verify(exactly = 0) { cancelTask.cancel(any()) }
        verify { archiveStore.delete(storageKey) }
        verifyCancelled()
    }

    @Test
    fun `Given a store that cannot take the archive back, Then the import is cancelled all the same`() {
        // Given: deleteIfExists throws, which would otherwise answer a DELETE with a 500 on a task that
        // is already cancelled. The periodic sweep is the guarantor of the bytes (ADR 0003).
        every { repository.findById(importId) } returns
            importWith(state = UserDataImportState.PENDING, taskId = null)
        every { archiveStore.delete(storageKey) } throws IOException("permission denied")
        every { repository.save(any()) } answers { firstArg() }

        // When
        canceller.cancel(user, importId)

        // Then
        verifyCancelled()
    }

    @Test
    fun `Given a running import, Then only the state is written and the archive is left to the runner`() {
        // Given: the fence stops the walk at the next pin, and the runner deletes the archive as it returns
        every { repository.findById(importId) } returns
            importWith(state = UserDataImportState.RUNNING, taskId = randomUUID())
        every { repository.save(any()) } answers { firstArg() }

        // When
        canceller.cancel(user, importId)

        // Then
        verify(exactly = 0) { archiveStore.delete(any()) }
        verify(exactly = 0) { archiveStore.discardPartialUpload(any()) }
        verify(exactly = 0) { cancelTask.cancel(any()) }
        verifyCancelled()
    }

    @Test
    fun `Given a terminal import, Then cancelling it releases nothing`() {
        // Given: enumerated from isTerminal, so a state added later is covered here rather than missed
        val terminalStates = UserDataImportState.entries.filter { it.isTerminal }
        assertTrue(terminalStates.isNotEmpty())

        terminalStates.forEach { state ->
            every { repository.findById(importId) } returns importWith(state = state)

            // When
            canceller.cancel(user, importId)
        }

        // Then
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { archiveStore.delete(any()) }
        verify(exactly = 0) { archiveStore.discardPartialUpload(any()) }
        verify(exactly = 0) { cancelTask.cancel(any()) }
    }
}
