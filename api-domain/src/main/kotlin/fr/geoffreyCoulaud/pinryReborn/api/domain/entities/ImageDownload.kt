package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import java.time.Instant
import java.util.UUID

data class ImageDownload(
    val pinId: UUID,
    val sourceUrl: String,
    val status: DownloadStatus,
    val reasonCode: DownloadReason?,
    val lastError: String?,
    val taskId: UUID,
    val requestedAt: Instant,
    val updatedAt: Instant,
)
