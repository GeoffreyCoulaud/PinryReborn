package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.ImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageDownloadRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.ImageDownloadModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.ImageDownloadModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QImageDownloadModel
import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class EbeanImageDownloadRepository(
    private val database: Database,
) : ImageDownloadRepositoryInterface {
    // No explicit beginTransaction here: delete+save and the bulk CAS updates run under the ambient
    // transaction when TransactionRunner opened one (Ebean binds it to the thread), else auto-commit.
    override fun upsertPending(pinId: UUID, sourceUrl: String, taskId: UUID, now: Instant): ImageDownload {
        QImageDownloadModel(database).pinId.equalTo(pinId).delete()
        val model = ImageDownload(
            pinId = pinId, sourceUrl = sourceUrl, status = DownloadStatus.PENDING, reasonCode = null,
            lastError = null, taskId = taskId, requestedAt = now, updatedAt = now,
        ).toModel()
        database.save(model)
        return model.toDomain()
    }

    override fun findByPinId(pinId: UUID): ImageDownload? =
        QImageDownloadModel(database).pinId.equalTo(pinId).findOne()?.toDomain()

    override fun markFailed(pinId: UUID, reason: DownloadReason, now: Instant): Boolean =
        pendingRows(pinId)
            .asUpdate()
            .set("status", DownloadStatus.FAILED.name)
            .set("reasonCode", reason.name)
            .set("updatedAt", now)
            .update() > 0

    override fun recordLastError(pinId: UUID, lastError: String, now: Instant): Boolean =
        pendingRows(pinId)
            .asUpdate()
            .set("lastError", lastError)
            .set("updatedAt", now)
            .update() > 0

    override fun deleteIfPending(pinId: UUID): Int = pendingRows(pinId).delete()

    override fun deleteByPinId(pinId: UUID) {
        QImageDownloadModel(database).pinId.equalTo(pinId).delete()
    }

    private fun pendingRows(pinId: UUID) =
        QImageDownloadModel(database).pinId.equalTo(pinId).status.equalTo(DownloadStatus.PENDING.name)
}
