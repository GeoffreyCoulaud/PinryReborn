package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.PassthroughTransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.CancelTask
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UserDataExportDeleterTest : BaseTest() {
    private val repository = mockk<UserDataExportRepositoryInterface>()
    private val archiveStore = mockk<ExportArchiveStore>()

    // Left unstubbed on purpose: a cancellation this use case must not attempt blows the test up on
    // the call itself, before any `verify` gets to be forgotten.
    private val cancelTask = mockk<CancelTask>()
    private val transactions = PassthroughTransactionRunner()
    private val deleter =
        UserDataExportDeleter(
            UserDataExportGetter(repository),
            repository,
            archiveStore,
            cancelTask,
            transactions,
        )

    private val user = User(id = randomUUID(), name = "alice", createdAt = TestTime.now)
    private val exportId = randomUUID()
    private val now = Instant.parse("2026-07-22T10:00:00Z")
    private val storageKey = "exports/$exportId.zip"

    /** The key a build derives, which is where a promote whose transaction rolled back left bytes. */
    private val derivedKey = ExportArchiveKey.forExport(exportId, FORMAT.fileExtension)

    /** The rows as the store holds them, so a refusal is read as the row it left rather than as a call. */
    private val rows = mutableMapOf<UUID, UserDataExport>()

    private fun exportWith(
        state: UserDataExportState,
        taskId: UUID? = null,
        storageKey: String? = null,
    ) = UserDataExport(
        id = exportId, userId = user.id, state = state, formatVersion = 1, requestedAt = now,
        taskId = taskId, storageKey = storageKey,
    )

    private fun stored(): UserDataExport? = rows[exportId]

    /** Which transaction each read and each write ran in, `null` outside one: a fence is one number. */
    private val readInTransactions = mutableListOf<Int?>()
    private val writtenInTransactions = mutableListOf<Int?>()

    private fun stubRow(row: UserDataExport) {
        rows[row.id] = row
        every { repository.findById(any()) } answers {
            readInTransactions += transactions.current
            rows[firstArg<UUID>()]
        }
    }

    /** The racing actor committing between the owner check and the fence, which only the fence sees. */
    private fun stubRacedRow(read: UserDataExport, raced: UserDataExport?) {
        rows[read.id] = read
        every { repository.findById(any()) } answers {
            if (!transactions.inside) return@answers read
            if (raced == null) rows.remove(read.id) else rows[read.id] = raced
            raced
        }
    }

    private fun stubRowWrites() {
        every { repository.save(any()) } answers {
            writtenInTransactions += transactions.current
            firstArg<UserDataExport>().also { row -> rows[row.id] = row }
        }
    }

    /** Read only where the key is derived rather than read off the row, which is the PENDING arm. */
    private fun stubArchiveFormat() {
        every { archiveStore.format } returns FORMAT
    }

    @Test
    fun `Given a deletion being written, Then the row is read and written in one transaction`() {
        // Given: the predicate alone holds against two successive transactions, and a build landing
        // between them is restored by merge, which writes every column of the copy it is handed. The
        // single connection serialises each statement, not a pair (`docs/adr/0016`, decision 1).
        stubRow(exportWith(state = UserDataExportState.FAILED))
        stubRowWrites()

        // When: a FAILED export, so the write is the whole use case and no release follows it
        deleter.delete(user, exportId)

        // Then: the owner check reads outside any transaction, the fence in the one it writes in
        val fenced = writtenInTransactions.single()
        assertNotNull(fenced, "the deletion should write inside a transaction")
        assertEquals(listOf(null, fenced), readInTransactions)
    }

    @Test
    fun `Given a pending export, Then deleting it cancels the task and marks it DELETED`() {
        // Given
        val taskId = randomUUID()
        stubRow(exportWith(state = UserDataExportState.PENDING, taskId = taskId))
        stubRowWrites()
        stubArchiveFormat()
        every { cancelTask.cancel(taskId) } returns true
        every { archiveStore.delete(derivedKey) } just runs

        // When
        deleter.delete(user, exportId)

        // Then
        verify { cancelTask.cancel(taskId) }
        assertEquals(UserDataExportState.DELETED, stored()?.state)
    }

    @Test
    fun `Given a pending export with no task id, Then no cancellation is attempted`() {
        // Given: the column is nullable because the row exists before its task does
        stubRow(exportWith(state = UserDataExportState.PENDING, taskId = null))
        stubRowWrites()
        stubArchiveFormat()
        every { archiveStore.delete(derivedKey) } just runs

        // When
        deleter.delete(user, exportId)

        // Then
        verify(exactly = 0) { cancelTask.cancel(any()) }
        assertEquals(UserDataExportState.DELETED, stored()?.state)
    }

    @Test
    fun `Given a pending export, Then the bytes a rolled-back promote left are released`() {
        // Given: the row names no key, so only the derivation can reach the residue ADR 0017 decision
        // 2 admits. The two transactions serialise, so a live attempt refuses at its own fence.
        stubRow(exportWith(state = UserDataExportState.PENDING, taskId = null))
        stubRowWrites()
        stubArchiveFormat()
        every { archiveStore.delete(derivedKey) } just runs

        // When
        deleter.delete(user, exportId)

        // Then
        verify { archiveStore.delete(derivedKey) }
        assertEquals(UserDataExportState.DELETED, stored()?.state)
    }

    @Test
    fun `Given a store that refuses a pending export's residue, Then the deletion still succeeds`() {
        // Given: residue cleanup, not the user's operation, so a disk failure must not answer 500
        stubRow(exportWith(state = UserDataExportState.PENDING, taskId = null))
        stubRowWrites()
        stubArchiveFormat()
        every { archiveStore.delete(derivedKey) } throws IOException("permission denied")

        // When / Then: no exception escapes
        deleter.delete(user, exportId)
        assertEquals(UserDataExportState.DELETED, stored()?.state)
    }

    @Test
    fun `Given a ready export, Then deleting it removes the bytes and marks it DELETED`() {
        // Given
        stubRow(exportWith(state = UserDataExportState.READY, storageKey = storageKey))
        stubRowWrites()
        every { archiveStore.delete(storageKey) } just runs

        // When
        deleter.delete(user, exportId)

        // Then
        verify { archiveStore.delete(storageKey) }
        assertEquals(UserDataExportState.DELETED, stored()?.state)
    }

    @Test
    fun `Given a store that refuses a ready export's bytes, Then the failure reaches the caller`() {
        // Given: this delete IS the user's DELETE, not a side-effect cleanup, so it keeps propagating
        // while the two residue paths around it do not
        stubRow(exportWith(state = UserDataExportState.READY, storageKey = storageKey))
        stubRowWrites()
        every { archiveStore.delete(storageKey) } throws IOException("permission denied")

        // When / Then
        assertThrows(IOException::class.java) { deleter.delete(user, exportId) }
    }

    @Test
    fun `Given a failed export, Then it is marked DELETED and nothing is released`() {
        // Given: a failure holds no bytes and its attempt has settled, so the state is the one write
        stubRow(exportWith(state = UserDataExportState.FAILED))
        stubRowWrites()

        // When
        deleter.delete(user, exportId)

        // Then
        assertEquals(UserDataExportState.DELETED, stored()?.state)
        verify(exactly = 0) { archiveStore.delete(any()) }
        verify(exactly = 0) { cancelTask.cancel(any()) }
    }

    @Test
    fun `Given an export already gone, Then deleting it releases nothing`() {
        // Given: enumerated from isGone, so a state added later is covered here rather than missed
        val goneStates = UserDataExportState.entries.filter { it.isGone }
        assertTrue(goneStates.isNotEmpty())

        goneStates.forEach { state ->
            stubRow(exportWith(state = state))

            // When
            deleter.delete(user, exportId)

            // Then
            assertEquals(state, stored()?.state)
        }
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { archiveStore.delete(any()) }
        verify(exactly = 0) { cancelTask.cancel(any()) }
    }

    @Test
    fun `Given a superseded export naming a key, Then deleting it releases those bytes`() {
        // Given: a supersede keeps its key since the export build completion lot, so this is the first
        // gone state that reaches the stranded release with something to release
        stubRow(exportWith(state = UserDataExportState.SUPERSEDED, storageKey = storageKey))
        every { archiveStore.delete(storageKey) } just runs

        // When
        deleter.delete(user, exportId)

        // Then: the fence refuses, so the release is the whole request and no write follows it
        verify { archiveStore.delete(storageKey) }
        assertEquals(UserDataExportState.SUPERSEDED, stored()?.state)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `Given a store that refuses a superseded export's bytes, Then the deletion still succeeds`() {
        // Given: the row is already gone to its owner, so this release repairs residue rather than
        // performing the request, and a disk that refuses must not turn a 204 into a 500
        stubRow(exportWith(state = UserDataExportState.SUPERSEDED, storageKey = storageKey))
        every { archiveStore.delete(storageKey) } throws IOException("permission denied")

        // When / Then: no exception escapes, and pass 3 of the sweep remains the guaranteed repair
        deleter.delete(user, exportId)
        assertEquals(UserDataExportState.SUPERSEDED, stored()?.state)
    }

    @Test
    fun `Given a deletion whose release failed, Then deleting again frees the bytes it left`() {
        // Given: the state moves before the bytes, so a disk failure answered the first DELETE with a
        // 500 over a row already DELETED. Nothing else reclaims those bytes: ReapOrphanedStorage keys
        // on row absence, and the row is still there naming them.
        stubRow(exportWith(state = UserDataExportState.DELETED, storageKey = storageKey))
        every { archiveStore.delete(storageKey) } just runs

        // When
        deleter.delete(user, exportId)

        // Then: the fence still refuses, so the retry is a release and not a second write
        verify { archiveStore.delete(storageKey) }
        assertEquals(UserDataExportState.DELETED, stored()?.state)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `Given a pending export completed while the request ran, Then the archive it found is released`() {
        // Given: the build publishes between the owner check and the fence, so the state the request
        // read is one state old and the bytes it has to release did not exist when it started.
        val read = exportWith(state = UserDataExportState.PENDING, taskId = randomUUID())
        stubRacedRow(read, read.copy(state = UserDataExportState.READY, storageKey = storageKey))
        stubRowWrites()
        every { archiveStore.delete(storageKey) } just runs

        // When
        deleter.delete(user, exportId)

        // Then: deciding from the copy read first would cancel a task that finished and leave the bytes
        assertEquals(UserDataExportState.DELETED, stored()?.state)
        verify { archiveStore.delete(storageKey) }
        verify(exactly = 0) { cancelTask.cancel(any()) }
    }

    @Test
    fun `Given a ready export expired while the request ran, Then nothing is written over it`() {
        // Given: the retention sweep takes the row in that window. The bytes are already gone, so a
        // deletion written over it would only lose the reason they went.
        val read = exportWith(state = UserDataExportState.READY, storageKey = storageKey)
        stubRacedRow(read, read.copy(state = UserDataExportState.EXPIRED))

        // When
        deleter.delete(user, exportId)

        // Then
        assertEquals(UserDataExportState.EXPIRED, stored()?.state)
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { archiveStore.delete(any()) }
    }

    @Test
    fun `Given an export erased while the request ran, Then nothing is released`() {
        // Given: the account deletion cleaner drops the row in that window, and merge is an upsert,
        // so a fence testing the copy read first would write the row back into existence.
        val read = exportWith(state = UserDataExportState.READY, storageKey = storageKey)
        stubRacedRow(read, null)

        // When
        deleter.delete(user, exportId)

        // Then
        assertNull(stored())
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { archiveStore.delete(any()) }
    }

    @Test
    fun `Given a build that stamped its key while the request ran, Then the deleted row still names those bytes`() {
        // Given: the build stamps the key it promotes onto in that same window, and writing the copy
        // read first would erase the only name the bytes it leaves behind have.
        val taskId = randomUUID()
        val read = exportWith(state = UserDataExportState.PENDING, taskId = taskId)
        stubRacedRow(read, read.copy(storageKey = storageKey))
        stubRowWrites()
        stubArchiveFormat()
        every { cancelTask.cancel(taskId) } returns true
        every { archiveStore.delete(derivedKey) } just runs

        // When
        deleter.delete(user, exportId)

        // Then
        assertEquals(UserDataExportState.DELETED, stored()?.state)
        assertEquals(storageKey, stored()?.storageKey)
    }

    private companion object {
        private val FORMAT = ArchiveFormat("application/zip", "zip")
    }
}
