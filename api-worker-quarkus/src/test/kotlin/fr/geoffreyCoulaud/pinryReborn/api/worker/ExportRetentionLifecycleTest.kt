package fr.geoffreyCoulaud.pinryReborn.api.worker

import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.ExportSweepCounts
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.ReapExpiredUserDataExports
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

class ExportRetentionLifecycleTest {
    private val reap: ReapExpiredUserDataExports = mockk(relaxed = true)
    private val scheduler: PeriodicScheduler = mockk(relaxed = true)
    private val config: ExportsConfig = mockk()

    private fun lifecycle() = ExportRetentionLifecycle(reap, scheduler, config)

    @Test
    fun `Given startup, Then it sweeps immediately and schedules the periodic purge`() {
        // Given
        every { config.purgeInterval() } returns Duration.ofSeconds(1)
        // When
        lifecycle().start()
        // Then
        verify { reap.reap() }
        verify { scheduler.scheduleWithFixedDelay(any(), 1000L, 1000L, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `Given a purge interval shorter than 2ms, Then the interval is clamped to at least 1ms`() {
        // Given
        every { config.purgeInterval() } returns Duration.ZERO
        // When
        lifecycle().start()
        // Then
        verify { scheduler.scheduleWithFixedDelay(any(), 1L, 1L, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `Given a reap failure, Then safeReap swallows it`() {
        // Given
        every { reap.reap() } throws RuntimeException("boom")
        // When / Then (no exception escapes)
        lifecycle().safeReap()
        verify { reap.reap() }
    }

    @Test
    fun `Given a clean reap, Then safeReap delegates once`() {
        // Given
        every { reap.reap() } returns ExportSweepCounts(failed = 0, expired = 0, reclaimed = 0)
        // When
        lifecycle().safeReap()
        // Then
        verify(exactly = 1) { reap.reap() }
    }

    @Test
    fun `Given a reap that throws at startup, Then the application still starts and keeps its schedule`() {
        // Given
        every { config.purgeInterval() } returns Duration.ofSeconds(1)
        every { reap.reap() } throws RuntimeException("boom")
        // When (no exception escapes)
        lifecycle().start()
        // Then
        verify { scheduler.scheduleWithFixedDelay(any(), 1000L, 1000L, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `Given a reap that moved rows, Then one line reports the count of each pass`() {
        // Given
        every { reap.reap() } returns ExportSweepCounts(failed = 1, expired = 2, reclaimed = 3)
        val reported = mutableListOf<String>()
        // When
        lifecycle().safeReap { reported += it }
        // Then
        assertEquals(1, reported.size, "one line per sweep, got $reported")
        val eachCount = listOf("1 failed", "2 expired", "3 reclaimed")
        assertTrue(
            eachCount.all { reported.first().contains(it) },
            "the sweep line must name every pass count, got: ${reported.first()}",
        )
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
        every { config.purgeInterval() } returns Duration.ofSeconds(1)
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
