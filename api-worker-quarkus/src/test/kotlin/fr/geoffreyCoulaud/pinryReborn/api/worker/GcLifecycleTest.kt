package fr.geoffreyCoulaud.pinryReborn.api.worker

import fr.geoffreyCoulaud.pinryReborn.api.usecases.ReapExpiredSessionTokens
import fr.geoffreyCoulaud.pinryReborn.api.usecases.ReapOrphanedStorage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.ReapTombstonedAccounts
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.ReapTerminalTasks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class GcLifecycleTest {
    private val reapExpiredSessionTokens = mockk<ReapExpiredSessionTokens>(relaxed = true)
    private val reapOrphanedStorage = mockk<ReapOrphanedStorage>(relaxed = true)
    private val reapTombstonedAccounts = mockk<ReapTombstonedAccounts>(relaxed = true)
    private val reapTerminalTasks = mockk<ReapTerminalTasks>(relaxed = true)
    private val scheduler = mockk<ScheduledExecutorService>(relaxed = true)
    private val config = mockk<GcConfig>()

    private fun lifecycle() = GcLifecycle(
        reapExpiredSessionTokens = reapExpiredSessionTokens,
        reapOrphanedStorage = reapOrphanedStorage,
        reapTombstonedAccounts = reapTombstonedAccounts,
        reapTerminalTasks = reapTerminalTasks,
        scheduler = scheduler,
        config = config,
    )

    @Test
    fun `Given startup, Then it runs every sweep once and schedules safeAll on the GC scheduler`() {
        // Given
        every { config.interval() } returns Duration.ofSeconds(1)
        // When
        lifecycle().start()
        // Then: each sweep ran once on startup via safeAll ...
        verify(exactly = 1) { reapExpiredSessionTokens.reap() }
        verify(exactly = 1) { reapOrphanedStorage.reap() }
        verify(exactly = 1) { reapTombstonedAccounts.reap() }
        verify(exactly = 1) { reapTerminalTasks.reap() }
        // ... and safeAll is scheduled at the config interval (initial and fixed delay)
        verify { scheduler.scheduleWithFixedDelay(any(), 1000L, 1000L, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `Given an interval shorter than 2ms, Then the interval is clamped to at least 1ms`() {
        // Given
        every { config.interval() } returns Duration.ZERO
        // When
        lifecycle().start()
        // Then
        verify { scheduler.scheduleWithFixedDelay(any(), 1L, 1L, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `Given every sweep succeeds, Then safeAll runs all four once`() {
        // Given: no mock throws, so every sweep covers its try arm
        // When
        lifecycle().safeAll()
        // Then: each sweep's try arm ran exactly once
        verify(exactly = 1) { reapExpiredSessionTokens.reap() }
        verify(exactly = 1) { reapOrphanedStorage.reap() }
        verify(exactly = 1) { reapTombstonedAccounts.reap() }
        verify(exactly = 1) { reapTerminalTasks.reap() }
    }

    @Test
    fun `Given every sweep throws, Then safeAll isolates each and still runs the rest`() {
        // Given: every sweep throws; each catch arm must run so the next sweep is still attempted
        every { reapExpiredSessionTokens.reap() } throws RuntimeException("tokens boom")
        every { reapOrphanedStorage.reap() } throws RuntimeException("orphan boom")
        every { reapTombstonedAccounts.reap() } throws RuntimeException("tomb boom")
        every { reapTerminalTasks.reap() } throws RuntimeException("tasks boom")

        // When / Then: no exception escapes (each throw is caught by its own catch arm and logged
        // at ERROR; the log itself is not asserted here, matching ExportRetentionLifecycleTest)
        lifecycle().safeAll()

        // Then: every sweep was attempted despite every prior sweep throwing
        verify(exactly = 1) { reapExpiredSessionTokens.reap() }
        verify(exactly = 1) { reapOrphanedStorage.reap() }
        verify(exactly = 1) { reapTombstonedAccounts.reap() }
        verify(exactly = 1) { reapTerminalTasks.reap() }
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
        every { config.interval() } returns Duration.ofSeconds(1)
        // When
        lifecycle().onStart(mockk<StartupEvent>())
        // Then
        verify { reapExpiredSessionTokens.reap() }
    }

    @Test
    fun `Given the CDI shutdown event, Then onStop delegates to stop`() {
        // When
        lifecycle().onStop(mockk<ShutdownEvent>())
        // Then
        verify { scheduler.shutdown() }
    }
}
