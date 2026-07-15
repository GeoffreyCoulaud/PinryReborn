package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.ImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class PinImageStateTest {
    private val pinId = randomUUID()
    private fun image() =
        Image(randomUUID(), pinId, "image/png", 1, 1, false, 1, "h", "originals/x/$pinId/i.png", Instant.EPOCH)
    private fun download(status: DownloadStatus, reason: DownloadReason? = null) =
        ImageDownload(pinId, "https://x", status, reason, null, randomUUID(), Instant.EPOCH, Instant.EPOCH)

    @Test fun `Given no image and no download, Then NONE`() {
        assertEquals(PinImageStatus.NONE, PinImageState.derive(null, null).status)
    }
    @Test fun `Given no image and a PENDING download, Then PENDING`() {
        assertEquals(PinImageStatus.PENDING, PinImageState.derive(null, download(DownloadStatus.PENDING)).status)
    }
    @Test fun `Given no image and a FAILED download, Then FAILED with the reason`() {
        val s = PinImageState.derive(null, download(DownloadStatus.FAILED, DownloadReason.ACCESS_DENIED))
        assertEquals(PinImageStatus.FAILED, s.status)
        assertEquals(DownloadReason.ACCESS_DENIED, s.reasonCode)
    }
    @Test fun `Given an image and no download, Then READY with no replacement`() {
        val s = PinImageState.derive(image(), null)
        assertEquals(PinImageStatus.READY, s.status)
        assertNull(s.replacement)
    }
    @Test fun `Given an image and a PENDING download, Then READY with a PENDING replacement`() {
        val s = PinImageState.derive(image(), download(DownloadStatus.PENDING))
        assertEquals(PinImageStatus.READY, s.status)
        assertEquals(DownloadStatus.PENDING, s.replacement?.status)
    }
    @Test fun `Given an image and a FAILED download, Then READY with a FAILED replacement carrying the reason`() {
        val s = PinImageState.derive(image(), download(DownloadStatus.FAILED, DownloadReason.ACCESS_DENIED))
        assertEquals(PinImageStatus.READY, s.status)
        assertEquals(DownloadStatus.FAILED, s.replacement?.status)
        assertEquals(DownloadReason.ACCESS_DENIED, s.replacement?.reasonCode)
    }
}
