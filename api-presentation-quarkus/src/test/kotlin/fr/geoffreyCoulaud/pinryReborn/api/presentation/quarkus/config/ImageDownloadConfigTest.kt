package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class ImageDownloadConfigTest {
    @Test
    fun `Given a config implementation, Then its accessors are readable`() {
        // Given
        val config = object : ImageDownloadConfig {
            override fun connectTimeout() = Duration.ofSeconds(5)
            override fun requestTimeout() = Duration.ofSeconds(30)
            override fun maxRedirects() = 5
            override fun allowPrivateAddresses() = false
        }
        // Then
        assertEquals(Duration.ofSeconds(5), config.connectTimeout())
        assertEquals(Duration.ofSeconds(30), config.requestTimeout())
        assertEquals(5, config.maxRedirects())
        assertEquals(false, config.allowPrivateAddresses())
    }
}
