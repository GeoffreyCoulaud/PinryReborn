package fr.geoffreyCoulaud.pinryReborn.api.domain.tasks

import java.time.Instant

data class NewTask(
    val kind: String,
    val payload: String,
    val availableAt: Instant,
    val priority: Int = 0,
    val maxAttempts: Int,
    val dedupKey: String? = null,
)
