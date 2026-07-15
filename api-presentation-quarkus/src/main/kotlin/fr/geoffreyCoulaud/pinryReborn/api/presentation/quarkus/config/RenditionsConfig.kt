package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault

@ConfigMapping(prefix = "images.renditions", namingStrategy = ConfigMapping.NamingStrategy.SNAKE_CASE)
interface RenditionsConfig {
    @WithDefault("112")
    fun tiny(): Int

    @WithDefault("240")
    fun small(): Int

    @WithDefault("480")
    fun medium(): Int

    @WithDefault("960")
    fun large(): Int

    @WithDefault("80")
    fun webpQuality(): Int
}
