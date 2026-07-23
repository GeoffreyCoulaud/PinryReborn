package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.AuditedBaseModel
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "tasks")
class TaskModel
    @Suppress("LongParameterList")
    constructor(
        id: UUID,
        var kind: String,
        var payload: String,
        var state: String,
        var priority: Int,
        var availableAt: Instant,
        var attempts: Int,
        var maxAttempts: Int,
        var leaseId: String? = null,
        var leaseExpiresAt: Instant? = null,
        var cancelRequested: Boolean = false,
        var dedupKey: String? = null,
        var lastError: String? = null,
    ) : AuditedBaseModel(id = id) {
    @Version
    var version: Long = 0
}
