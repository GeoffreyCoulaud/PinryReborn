package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.BaseModel
import io.ebean.annotation.Index
import jakarta.persistence.Entity
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Suppress("LongParameterList")
@Entity
@Table(name = "user_data_exports")
// Enforces "at most one PENDING export per user": a plain unique index on user_id would forbid a
// second export forever, so the index is partial, which only `definition` can spell.
@Index(
    name = "uq_user_data_exports_pending",
    columnNames = ["user_id"],
    unique = true,
    definition =
        "create unique index uq_user_data_exports_pending " +
            "on user_data_exports (user_id) where state = 'PENDING'",
)
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
