package fr.geoffreyCoulaud.pinryReborn.api.domain.tasks

import java.util.UUID

data class ClaimedTask(
    val id: UUID,
    val kind: String,
    val payload: String,
    val attempts: Int,
    val maxAttempts: Int,
    val leaseId: String,
    val cancelRequested: Boolean,
)
