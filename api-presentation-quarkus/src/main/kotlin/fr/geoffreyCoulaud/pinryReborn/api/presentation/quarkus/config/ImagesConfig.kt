package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault

@ConfigMapping(prefix = "images", namingStrategy = ConfigMapping.NamingStrategy.SNAKE_CASE)
interface ImagesConfig {
    @WithDefault("/var/lib/pinry/images")
    fun dataDir(): String

    @WithDefault("31457280") // 30 MiB
    fun maxFileBytes(): Long

    @WithDefault("50000000") // 50 megapixels
    fun maxPixels(): Long
}
