package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.PassthroughTransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReapExpiredUserDataExportsTest : BaseTest() {
    private val repository = mockk<UserDataExportRepositoryInterface>()
    private val archiveStore = mockk<ExportArchiveStore>()
    private val clock = mockk<Clock>()
    private val transactions = PassthroughTransactionRunner()
    private val now = Instant.parse("2026-07-22T10:00:00Z")
    private val stagedFileMaxAge: Duration = Duration.ofHours(6)
    private val reaper =
        ReapExpiredUserDataExports(repository, archiveStore, clock, transactions, stagedFileMaxAge)

    /** The rows as the store holds them, so a refusal is read as the row it left rather than as a call. */
    private val rows = mutableMapOf<UUID, UserDataExport>()
    private val deletedArchives = mutableListOf<String>()
    private var orphanCutoff: Instant? = null

    private fun expiredExport(storageKey: String? = null) =
        UserDataExport(
            id = randomUUID(), userId = randomUUID(), state = UserDataExportState.READY,
            formatVersion = 1, requestedAt = now.minus(Duration.ofDays(10)), storageKey = storageKey,
        ).also { rows[it.id] = it }

    private fun stored(id: UUID): UserDataExport = requireNotNull(rows[id])

    /** What every run reads, whether or not it finds anything: the selection and the staged-file sweep. */
    private fun stubSweep() {
        every { clock.now() } returns now
        every { repository.findExpiredReadyExports(now) } answers {
            rows.values.filter { row -> row.state == UserDataExportState.READY }
        }
        every { archiveStore.discardOrphanedStagedFiles(any()) } answers {
            orphanCutoff = firstArg()
            0
        }
    }

    /** Only the runs that act on a row reach these, and `BaseTest` fails a stub nothing reached. */
    private fun stubRowWrites() {
        every { repository.findById(any()) } answers { rows[firstArg<UUID>()] }
        every { repository.save(any()) } answers { firstArg<UserDataExport>().also { row -> rows[row.id] = row } }
    }

    private fun stubArchiveDeletion() {
        every { archiveStore.delete(any()) } answers { deletedArchives += firstArg<String>() }
    }

    /**
     * The racing actor committing between the batch selection and this row's write, which only a read
     * inside the write's transaction sees: answered outside it, the fence would pass unfenced code.
     */
    private fun stubRacedRow(raced: UserDataExport, state: UserDataExportState) {
        every { repository.findById(raced.id) } answers {
            if (!transactions.inside) return@answers stored(raced.id)
            stored(raced.id).copy(state = state).also { row -> rows[row.id] = row }
        }
    }

    @Test
    fun `Given a ready export past its expiry, Then it becomes EXPIRED and its bytes are deleted`() {
        // Given
        stubSweep()
        stubRowWrites()
        stubArchiveDeletion()
        val export = expiredExport(storageKey = "exports/e1.zip")

        // When
        val count = reaper.reap()

        // Then
        assertEquals(1, count)
        assertEquals(UserDataExportState.EXPIRED, stored(export.id).state)
        assertEquals(listOf("exports/e1.zip"), deletedArchives)
    }

    @Test
    fun `Given an expired export without a storage key, Then no delete is attempted`() {
        // Given
        stubSweep()
        stubRowWrites()
        val export = expiredExport(storageKey = null)

        // When
        val count = reaper.reap()

        // Then
        assertEquals(1, count)
        assertEquals(UserDataExportState.EXPIRED, stored(export.id).state)
        verify(exactly = 0) { archiveStore.delete(any()) }
    }

    @Test
    fun `Given no expired export, Then nothing is written and zero is returned`() {
        // Given
        stubSweep()

        // When
        val count = reaper.reap()

        // Then
        assertEquals(0, count)
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { archiveStore.delete(any()) }
    }

    @Test
    fun `Given any run, Then staged files past their age are discarded`() {
        // Given: bytes whose export row never existed, which no row-driven path can see
        stubSweep()

        // When
        reaper.reap()

        // Then
        assertEquals(now.minus(stagedFileMaxAge), orphanCutoff)
    }

    @Test
    fun `Given an export that moved on while the sweep read it, Then nothing is written over it`() {
        // Given: the owner's DELETE and a new request's SUPERSEDED are the two met in practice, and
        // the deletion already released the bytes this run would otherwise delete a second time.
        // Ranged over every state, a single-state refusal telling state == READY from no looser one.
        stubSweep()
        val racedStates = UserDataExportState.entries.filter { it != UserDataExportState.READY }
        assertTrue(racedStates.isNotEmpty())

        racedStates.forEach { state ->
            rows.clear()
            val raced = expiredExport(storageKey = "exports/raced.zip")
            stubRacedRow(raced, state)

            // When
            val count = reaper.reap()

            // Then
            assertEquals(0, count)
            assertEquals(state, stored(raced.id).state)
        }
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { archiveStore.delete(any()) }
    }

    @Test
    fun `Given two expired exports and one of them raced, Then only the row moved is counted`() {
        // Given: a fully refused sweep and a fully successful one would otherwise return the same number
        stubSweep()
        stubRowWrites()
        stubArchiveDeletion()
        val raced = expiredExport(storageKey = "exports/raced.zip")
        val swept = expiredExport(storageKey = "exports/swept.zip")
        stubRacedRow(raced, UserDataExportState.DELETED)

        // When
        val count = reaper.reap()

        // Then
        assertEquals(1, count)
        assertEquals(UserDataExportState.EXPIRED, stored(swept.id).state)
        assertEquals(UserDataExportState.DELETED, stored(raced.id).state)
        assertEquals(listOf("exports/swept.zip"), deletedArchives)
    }

    @Test
    fun `Given one export the database refuses, Then the rest of the run still happens`() {
        // Given: item-level isolation, as ReapAbandonedUserDataImports has for the same reason
        stubSweep()
        stubRowWrites()
        stubArchiveDeletion()
        val refused = expiredExport(storageKey = "exports/refused.zip")
        val swept = expiredExport(storageKey = "exports/swept.zip")
        every { repository.save(match { it.id == refused.id }) } throws RuntimeException("db down")

        // When
        val count = reaper.reap()

        // Then
        assertEquals(1, count)
        assertEquals(UserDataExportState.EXPIRED, stored(swept.id).state)
        assertEquals(UserDataExportState.READY, stored(refused.id).state)
        assertEquals(listOf("exports/swept.zip"), deletedArchives)
    }
}
