package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.ImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import java.time.Instant
import java.util.UUID

interface ImageDownloadRepositoryInterface {
    /** Create-or-replace the pin's download row as PENDING with a fresh sourceUrl + taskId. */
    fun upsertPending(pinId: UUID, sourceUrl: String, taskId: UUID, now: Instant): ImageDownload

    fun findByPinId(pinId: UUID): ImageDownload?

    /** CAS on PENDING: set FAILED + reason. Returns true if a PENDING row was updated. */
    fun markFailed(pinId: UUID, reason: DownloadReason, now: Instant): Boolean

    /** CAS on PENDING: record the last transient error, keep PENDING. Returns true if updated. */
    fun recordLastError(pinId: UUID, lastError: String, now: Instant): Boolean

    /** CAS on PENDING: delete the row only if still PENDING. Returns the number of rows deleted (0 or 1). */
    fun deleteIfPending(pinId: UUID): Int

    /** Unconditional delete of the pin's download row (idempotent). */
    fun deleteByPinId(pinId: UUID)
}
