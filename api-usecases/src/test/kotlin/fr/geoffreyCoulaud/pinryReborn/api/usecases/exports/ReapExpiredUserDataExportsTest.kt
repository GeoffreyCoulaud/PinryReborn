package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import java.util.UUID.randomUUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReapExpiredUserDataExportsTest : BaseTest() {
    private val repository = mockk<UserDataExportRepositoryInterface>()
    private val archiveStore = mockk<ExportArchiveStore>()
    private val clock = mockk<Clock>()
    private val now = Instant.parse("2026-07-22T10:00:00Z")
    private val stagedFileMaxAge = Duration.ofHours(6)
    private val reaper = ReapExpiredUserDataExports(repository, archiveStore, clock, stagedFileMaxAge)

    private fun expiredExport(storageKey: String?) =
        UserDataExport(
            id = randomUUID(), userId = randomUUID(), state = UserDataExportState.READY,
            formatVersion = 1, requestedAt = now.minus(Duration.ofDays(10)), storageKey = storageKey,
        )

    @Test
    fun `Given a ready export past its expiry, Then its bytes are deleted and it becomes EXPIRED`() {
        // Given
        every { clock.now() } returns now
        val export = expiredExport(storageKey = "exports/e1.zip")
        every { repository.findExpiredReadyExports(now) } returns listOf(export)
        every { archiveStore.delete("exports/e1.zip") } just runs
        every { repository.save(any()) } answers { firstArg() }
        every { archiveStore.discardOrphanedStagedFiles(any()) } returns 0

        // When
        val count = reaper.reap()

        // Then
        assertEquals(1, count)
        verify { archiveStore.delete("exports/e1.zip") }
        verify { repository.save(match { it.id == export.id && it.state == UserDataExportState.EXPIRED }) }
    }

    @Test
    fun `Given an expired export without a storage key, Then no delete is attempted`() {
        // Given
        every { clock.now() } returns now
        val export = expiredExport(storageKey = null)
        every { repository.findExpiredReadyExports(now) } returns listOf(export)
        every { repository.save(any()) } answers { firstArg() }
        every { archiveStore.discardOrphanedStagedFiles(any()) } returns 0

        // When
        val count = reaper.reap()

        // Then
        assertEquals(1, count)
        verify(exactly = 0) { archiveStore.delete(any()) }
        verify { repository.save(match { it.id == export.id && it.state == UserDataExportState.EXPIRED }) }
    }

    @Test
    fun `Given no expired export, Then nothing is written and zero is returned`() {
        // Given
        every { clock.now() } returns now
        every { repository.findExpiredReadyExports(now) } returns emptyList()
        every { archiveStore.discardOrphanedStagedFiles(any()) } returns 0

        // When
        val count = reaper.reap()

        // Then
        assertEquals(0, count)
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { archiveStore.delete(any()) }
    }

    @Test
    fun `Given orphaned staged files, Then they are discarded`() {
        // Given
        every { clock.now() } returns now
        every { repository.findExpiredReadyExports(now) } returns emptyList()
        every { archiveStore.discardOrphanedStagedFiles(now.minus(stagedFileMaxAge)) } returns 3

        // When
        reaper.reap()

        // Then
        verify { archiveStore.discardOrphanedStagedFiles(now.minus(stagedFileMaxAge)) }
    }
}
