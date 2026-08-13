package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

@ConfigMapping(prefix = "auth", namingStrategy = ConfigMapping.NamingStrategy.SNAKE_CASE)
interface AuthConfig {
    @WithDefault("P30D")
    fun persistentTtl(): Duration

    @WithDefault("PT12H")
    fun ephemeralTtl(): Duration

    @WithDefault("PT30S")
    fun passwordChangeMinimumInterval(): Duration

    @WithDefault("0.75")
    fun renewThreshold(): Double

    @WithDefault("5")
    fun attemptLimitThreshold(): Int

    /** Block duration per step, saturating on the last one. */
    @WithDefault("PT30S,PT2M,PT10M")
    fun attemptLimitBackoff(): List<Duration>

    @WithDefault("PT15M")
    fun attemptLimitForgetAfter(): Duration

    @WithDefault("10000")
    fun attemptLimitMaxTrackedKeys(): Int
}
