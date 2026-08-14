package fr.geoffreyCoulaud.pinryReborn.api.worker

import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.ReapAbandonedUserDataImports
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

class ImportLifecycleTest {
    private val reap: ReapAbandonedUserDataImports = mockk(relaxed = true)
    private val scheduler: PeriodicScheduler = mockk(relaxed = true)
    private val config: ImportsConfig = mockk()

    private fun lifecycle() = ImportLifecycle(reap, scheduler, config)

    @Test
    fun `Given startup, Then it sweeps immediately and schedules the periodic sweep`() {
        // Given
        every { config.sweepInterval() } returns Duration.ofSeconds(1)
        // When
        lifecycle().start()
        // Then
        verify { reap.reap() }
        verify { scheduler.scheduleWithFixedDelay(any(), 1000L, 1000L, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `Given a sweep interval shorter than 2ms, Then the interval is clamped to at least 1ms`() {
        // Given
        every { config.sweepInterval() } returns Duration.ZERO
        // When
        lifecycle().start()
        // Then
        verify { scheduler.scheduleWithFixedDelay(any(), 1L, 1L, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `Given a sweep failure, Then safeReap swallows it`() {
        // Given
        every { reap.reap() } throws RuntimeException("boom")
        // When / Then (no exception escapes)
        lifecycle().safeReap()
        verify { reap.reap() }
    }

    @Test
    fun `Given a clean sweep, Then safeReap delegates once`() {
        // Given
        every { reap.reap() } returns 0
        // When
        lifecycle().safeReap()
        // Then
        verify(exactly = 1) { reap.reap() }
    }

    @Test
    fun `Given shutdown, Then it shuts the scheduler down`() {
        // When
        lifecycle().stop()
        // Then
        verify { scheduler.shutdown() }
    }

    @Test
    fun `Given the CDI startup event, Then onStart delegates to start`() {
        // Given
        every { config.sweepInterval() } returns Duration.ofSeconds(1)
        // When
        lifecycle().onStart(mockk<StartupEvent>())
        // Then
        verify { reap.reap() }
    }

    @Test
    fun `Given the CDI shutdown event, Then onStop delegates to stop`() {
        // When
        lifecycle().onStop(mockk<ShutdownEvent>())
        // Then
        verify { scheduler.shutdown() }
    }
}
