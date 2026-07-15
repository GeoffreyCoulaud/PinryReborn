package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.RenditionsConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RenditionSizeTest {
    @Test
    fun `Given a known name, Then fromName resolves it case-insensitively`() {
        assertEquals(RenditionSize.SMALL, RenditionSize.fromName("small"))
        assertEquals(RenditionSize.LARGE, RenditionSize.fromName("LARGE"))
    }

    @Test
    fun `Given an unknown name, Then fromName returns null`() {
        assertNull(RenditionSize.fromName("huge"))
    }

    @Test
    fun `Given the config, Then pxFrom returns the configured value for each size`() {
        val config = mockk<RenditionsConfig>()
        every { config.tiny() } returns 112
        every { config.small() } returns 240
        every { config.medium() } returns 480
        every { config.large() } returns 960
        assertEquals(112, RenditionSize.TINY.pxFrom(config))
        assertEquals(240, RenditionSize.SMALL.pxFrom(config))
        assertEquals(480, RenditionSize.MEDIUM.pxFrom(config))
        assertEquals(960, RenditionSize.LARGE.pxFrom(config))
    }
}
