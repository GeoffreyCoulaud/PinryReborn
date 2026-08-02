package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "image_download")
@Suppress("LongParameterList") // Ebean entity: every parameter is a persisted column.
class ImageDownloadModel(
    @Id var pinId: UUID,
    var sourceUrl: String,
    var status: String,
    var reasonCode: String?,
    var lastError: String?,
    var taskId: UUID,
    var requestedAt: Instant,
    var updatedAt: Instant,
)
