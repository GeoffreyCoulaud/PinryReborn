package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.BaseModel
import jakarta.persistence.Entity
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Suppress("LongParameterList")
@Entity
@Table(name = "user_data_exports")
class UserDataExportModel(
    id: UUID,
    @ManyToOne var user: UserModel,
    var state: String,
    var formatVersion: Int,
    var requestedAt: Instant,
    var taskId: UUID? = null,
    var completedAt: Instant? = null,
    var expiresAt: Instant? = null,
    var storageKey: String? = null,
    var byteSize: Long? = null,
    var sha256: String? = null,
    var mediaType: String? = null,
    var fileExtension: String? = null,
    var failureCode: String? = null,
) : BaseModel(id = id)
