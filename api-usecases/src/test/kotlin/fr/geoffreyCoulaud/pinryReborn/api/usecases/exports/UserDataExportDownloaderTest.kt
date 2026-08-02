package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ExportGoneError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ExportNotReadyError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import java.util.UUID.randomUUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class UserDataExportDownloaderTest : BaseTest() {
    private val getter = mockk<UserDataExportGetter>()
    private val archiveStore = mockk<ExportArchiveStore>()
    private val downloader = UserDataExportDownloader(getter, archiveStore)
    private val user = User(id = randomUUID(), name = "alice", createdAt = Instant.now())
    private val exportId = randomUUID()
    private val now = Instant.parse("2026-07-22T10:00:00Z")

    @Suppress("LongParameterList") // Test fixture builder: every parameter shapes a distinct scenario.
    private fun exportWith(
        state: UserDataExportState,
        storageKey: String? = "exports/e1.zip",
        mediaType: String? = "application/zip",
        fileExtension: String? = "zip",
        byteSize: Long? = 100L,
        sha256: String? = "abc123",
        completedAt: Instant? = now,
    ) = UserDataExport(
        id = exportId, userId = user.id, state = state, formatVersion = 1, requestedAt = now,
        storageKey = storageKey, mediaType = mediaType, fileExtension = fileExtension,
        byteSize = byteSize, sha256 = sha256, completedAt = completedAt,
    )

    @Test
    fun `Given a pending export, Then downloading it throws ExportNotReadyError`() {
        // Given
        every { getter.get(user, exportId) } returns exportWith(state = UserDataExportState.PENDING)

        // When / Then
        assertThrows(ExportNotReadyError::class.java) { downloader.open(user, exportId, 0) }
    }

    @Test
    fun `Given a failed export, Then downloading it throws ExportNotReadyError`() {
        // Given
        every { getter.get(user, exportId) } returns exportWith(state = UserDataExportState.FAILED)

        // When / Then
        assertThrows(ExportNotReadyError::class.java) { downloader.open(user, exportId, 0) }
    }

    @Test
    fun `Given an expired export, Then downloading it throws ExportGoneError`() {
        // Given
        every { getter.get(user, exportId) } returns exportWith(state = UserDataExportState.EXPIRED)

        // When / Then
        assertThrows(ExportGoneError::class.java) { downloader.open(user, exportId, 0) }
    }

    @Test
    fun `Given a ready export missing its storage key, Then downloading throws ExportNotReadyError`() {
        // Given
        every { getter.get(user, exportId) } returns
            exportWith(state = UserDataExportState.READY, storageKey = null)

        // When / Then
        assertThrows(ExportNotReadyError::class.java) { downloader.open(user, exportId, 0) }
    }

    @Test
    fun `Given a negative offset, Then downloading throws ExportNotReadyError`() {
        // Given
        every { getter.get(user, exportId) } returns exportWith(state = UserDataExportState.READY)

        // When / Then
        assertThrows(ExportNotReadyError::class.java) { downloader.open(user, exportId, -1) }
    }

    @Test
    fun `Given an offset past the end, Then downloading throws ExportNotReadyError`() {
        // Given
        every { getter.get(user, exportId) } returns exportWith(state = UserDataExportState.READY, byteSize = 100L)

        // When / Then
        assertThrows(ExportNotReadyError::class.java) { downloader.open(user, exportId, 100) }
    }

    @Test
    fun `Given a ready export, Then the stream is opened eagerly at the requested offset`() {
        // Given
        val export = exportWith(state = UserDataExportState.READY)
        every { getter.get(user, exportId) } returns export
        val stream: InputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        every { archiveStore.openStream("exports/e1.zip", 10) } returns stream

        // When
        val result = downloader.open(user, exportId, 10)

        // Then
        verify(exactly = 1) { archiveStore.openStream("exports/e1.zip", 10) }
        assertEquals(export.id, result.exportId)
        assertEquals(export.mediaType, result.mediaType)
        assertEquals(export.fileExtension, result.fileExtension)
        assertEquals(export.byteSize, result.totalByteSize)
        assertEquals(export.sha256, result.sha256)
        assertEquals(export.completedAt, result.completedAt)
        assertSame(stream, result.stream)
    }
}
