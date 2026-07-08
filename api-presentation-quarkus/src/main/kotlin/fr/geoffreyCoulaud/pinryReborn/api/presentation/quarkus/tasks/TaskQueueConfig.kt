package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

@ConfigMapping(prefix = "tasks", namingStrategy = ConfigMapping.NamingStrategy.SNAKE_CASE)
interface TaskQueueConfig {
    @WithDefault("4")
    fun workerCount(): Int

    @WithDefault("PT1S")
    fun pollInterval(): Duration

    @WithDefault("PT1M")
    fun leaseDuration(): Duration

    @WithDefault("PT1S")
    fun backoffBase(): Duration

    @WithDefault("PT5M")
    fun backoffCap(): Duration

    @WithDefault("5")
    fun defaultMaxAttempts(): Int

    @WithDefault("PT20S")
    fun shutdownDrainTimeout(): Duration
}
