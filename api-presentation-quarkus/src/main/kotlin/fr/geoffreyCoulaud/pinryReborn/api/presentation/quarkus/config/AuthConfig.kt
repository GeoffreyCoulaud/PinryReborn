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

    @WithDefault("0.75")
    fun renewThreshold(): Double
}
