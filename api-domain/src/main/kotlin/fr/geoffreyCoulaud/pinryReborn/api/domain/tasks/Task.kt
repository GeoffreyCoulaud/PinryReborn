package fr.geoffreyCoulaud.pinryReborn.api.domain.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Identifiable
import java.time.Instant
import java.util.UUID

data class Task(
    override val id: UUID,
    val kind: String,
    val payload: String,
    val state: TaskState,
    val priority: Int,
    val availableAt: Instant,
    val attempts: Int,
    val maxAttempts: Int,
    val leaseId: String?,
    val leaseExpiresAt: Instant?,
    val cancelRequested: Boolean,
    val dedupKey: String?,
    val lastError: String?,
) : Identifiable
