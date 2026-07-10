package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.ImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageDownloadRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.CancelTask
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class ClearPinDownloadTest {
    private val downloads: ImageDownloadRepositoryInterface = mockk(relaxed = true)
    private val cancelTask: CancelTask = mockk(relaxed = true)
    private val pinId = randomUUID()
    private val subject = ClearPinDownload(downloads, cancelTask)

    @Test fun `Given a download row, Then it cancels the task and deletes the row`() {
        val taskId = randomUUID()
        every { downloads.findByPinId(pinId) } returns
            ImageDownload(pinId, "https://x", DownloadStatus.PENDING, null, null, taskId, Instant.EPOCH, Instant.EPOCH)
        val result = subject.clear(pinId)
        assertTrue(result)
        verify { cancelTask.cancel(taskId) }
        verify { downloads.deleteByPinId(pinId) }
    }

    @Test fun `Given no download row, Then it does nothing`() {
        every { downloads.findByPinId(pinId) } returns null
        val result = subject.clear(pinId)
        assertFalse(result)
        verify(exactly = 0) { cancelTask.cancel(any()) }
        verify(exactly = 0) { downloads.deleteByPinId(any()) }
    }
}
