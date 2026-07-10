package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.EbeanImageDownloadRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class EbeanImageDownloadRepositoryTest : RepositoryTest() {
    private val repository = EbeanImageDownloadRepository(database)
    private val now = Instant.parse("2026-07-10T00:00:00Z")

    @Test
    fun `Given upsertPending, Then findByPinId returns a PENDING row`() {
        val pinId = randomUUID()
        val saved = repository.upsertPending(pinId, "https://x/i.png", randomUUID(), now)
        assertEquals(DownloadStatus.PENDING, saved.status)
        assertEquals(saved, repository.findByPinId(pinId))
    }

    @Test
    fun `Given an existing row, Then upsertPending replaces it with a fresh PENDING`() {
        val pinId = randomUUID()
        repository.upsertPending(pinId, "https://x/a.png", randomUUID(), now)
        repository.markFailed(pinId, DownloadReason.NOT_FOUND, now)
        val replaced = repository.upsertPending(pinId, "https://x/b.png", randomUUID(), now)
        assertEquals("https://x/b.png", replaced.sourceUrl)
        assertEquals(DownloadStatus.PENDING, repository.findByPinId(pinId)?.status)
        assertNull(repository.findByPinId(pinId)?.reasonCode)
    }

    @Test
    fun `Given a PENDING row, Then markFailed sets FAILED and returns true`() {
        val pinId = randomUUID()
        repository.upsertPending(pinId, "https://x/i.png", randomUUID(), now)
        assertTrue(repository.markFailed(pinId, DownloadReason.ACCESS_DENIED, now))
        val row = repository.findByPinId(pinId)
        assertEquals(DownloadStatus.FAILED, row?.status)
        assertEquals(DownloadReason.ACCESS_DENIED, row?.reasonCode)
    }

    @Test
    fun `Given no PENDING row, Then markFailed returns false`() {
        assertFalse(repository.markFailed(randomUUID(), DownloadReason.ACCESS_DENIED, now))
    }

    @Test
    fun `Given a PENDING row, Then recordLastError keeps PENDING and returns true`() {
        val pinId = randomUUID()
        repository.upsertPending(pinId, "https://x/i.png", randomUUID(), now)
        assertTrue(repository.recordLastError(pinId, "timeout", now))
        assertEquals(DownloadStatus.PENDING, repository.findByPinId(pinId)?.status)
        assertEquals("timeout", repository.findByPinId(pinId)?.lastError)
    }

    @Test
    fun `Given no PENDING row, Then recordLastError returns false`() {
        assertFalse(repository.recordLastError(randomUUID(), "x", now))
    }

    @Test
    fun `Given a PENDING row, Then deleteIfPending deletes it and returns 1`() {
        val pinId = randomUUID()
        repository.upsertPending(pinId, "https://x/i.png", randomUUID(), now)
        assertEquals(1, repository.deleteIfPending(pinId))
        assertNull(repository.findByPinId(pinId))
    }

    @Test
    fun `Given a FAILED row, Then deleteIfPending returns 0 and keeps the row`() {
        val pinId = randomUUID()
        repository.upsertPending(pinId, "https://x/i.png", randomUUID(), now)
        repository.markFailed(pinId, DownloadReason.NOT_FOUND, now)
        assertEquals(0, repository.deleteIfPending(pinId))
        assertEquals(DownloadStatus.FAILED, repository.findByPinId(pinId)?.status)
    }

    @Test
    fun `Given any row, Then deleteByPinId removes it and is a no-op when absent`() {
        val pinId = randomUUID()
        repository.upsertPending(pinId, "https://x/i.png", randomUUID(), now)
        repository.deleteByPinId(pinId)
        assertNull(repository.findByPinId(pinId))
        repository.deleteByPinId(randomUUID()) // must not throw
    }
}
