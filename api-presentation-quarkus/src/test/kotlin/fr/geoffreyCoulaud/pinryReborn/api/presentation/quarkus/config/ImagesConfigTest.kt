package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImagesConfigTest {
    @Test
    fun `Given a config implementation, Then its accessors are readable`() {
        // Given
        val config = object : ImagesConfig {
            override fun dataDir() = "/var/lib/pinry"
            override fun maxFileBytes() = 31_457_280L
            override fun maxPixels() = 50_000_000L
        }
        // Then
        assertEquals("/var/lib/pinry", config.dataDir())
        assertEquals(31_457_280L, config.maxFileBytes())
        assertEquals(50_000_000L, config.maxPixels())
    }
}
