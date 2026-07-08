package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import io.smallrye.config.ConfigMapping
import java.time.Duration

@ConfigMapping(prefix = "tasks", namingStrategy = ConfigMapping.NamingStrategy.SNAKE_CASE)
interface TaskQueueConfig {
    fun workerCount(): Int
    fun pollInterval(): Duration
    fun leaseDuration(): Duration
    fun backoffBase(): Duration
    fun backoffCap(): Duration
    fun defaultMaxAttempts(): Int
    fun shutdownDrainTimeout(): Duration
}
