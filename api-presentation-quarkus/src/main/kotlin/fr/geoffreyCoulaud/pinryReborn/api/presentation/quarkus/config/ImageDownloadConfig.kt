package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

@ConfigMapping(prefix = "images.download", namingStrategy = ConfigMapping.NamingStrategy.SNAKE_CASE)
interface ImageDownloadConfig {
    @WithDefault("PT5S")
    fun connectTimeout(): Duration

    @WithDefault("PT30S")
    fun requestTimeout(): Duration

    @WithDefault("5")
    fun maxRedirects(): Int

    // Escape hatch for trusted networks (e.g. a self-hoster pinning from a LAN NAS) and for
    // integration tests that fetch from a loopback origin. Default false = full Standard SSRF guard.
    @WithDefault("false")
    fun allowPrivateAddresses(): Boolean
}
