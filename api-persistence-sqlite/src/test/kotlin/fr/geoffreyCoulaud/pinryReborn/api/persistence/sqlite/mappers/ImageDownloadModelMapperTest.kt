package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.ImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.ImageDownloadModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.ImageDownloadModelMapper.toModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class ImageDownloadModelMapperTest {
    @Test
    fun `Given a PENDING download, Then toModel and toDomain round-trip its fields`() {
        val download = ImageDownload(
            pinId = randomUUID(), sourceUrl = "https://x/i.png", status = DownloadStatus.PENDING,
            reasonCode = null, lastError = null, taskId = randomUUID(),
            requestedAt = Instant.parse("2026-07-10T00:00:00Z"), updatedAt = Instant.parse("2026-07-10T00:00:01Z"),
        )
        assertEquals(download, download.toModel().toDomain())
    }

    @Test
    fun `Given a FAILED download with a reason, Then it round-trips the reason`() {
        val download = ImageDownload(
            pinId = randomUUID(), sourceUrl = "https://x/i.png", status = DownloadStatus.FAILED,
            reasonCode = DownloadReason.ACCESS_DENIED, lastError = "403", taskId = randomUUID(),
            requestedAt = Instant.parse("2026-07-10T00:00:00Z"), updatedAt = Instant.parse("2026-07-10T00:00:02Z"),
        )
        assertEquals(download, download.toModel().toDomain())
    }
}
