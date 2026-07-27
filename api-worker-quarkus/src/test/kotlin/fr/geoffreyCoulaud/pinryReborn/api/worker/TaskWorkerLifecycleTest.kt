package fr.geoffreyCoulaud.pinryReborn.api.worker

import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.ReapExpiredTasks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

class TaskWorkerLifecycleTest {
    private val dispatcher: TaskDispatcher = mockk(relaxed = true)
    private val reap: ReapExpiredTasks = mockk(relaxed = true)
    private val executor: WorkerExecutor = mockk()
    private val scheduler: PeriodicScheduler = mockk(relaxed = true)
    private val config: TaskQueueConfig = mockk()

    private fun lifecycle() = TaskWorkerLifecycle(dispatcher, reap, executor, scheduler, config)

    init {
        every { config.pollInterval() } returns Duration.ofSeconds(1)
        every { config.shutdownDrainTimeout() } returns Duration.ofSeconds(5)
    }

    @Test
    fun `Given startup, Then it reaps orphans and schedules both polling and periodic reaping`() {
        // Given
        every { config.leaseDuration() } returns Duration.ofSeconds(2)
        // When
        lifecycle().start()
        // Then
        verify { reap.reap() }
        verify { scheduler.scheduleWithFixedDelay(any(), 0L, 1000L, TimeUnit.MILLISECONDS) }
        verify { scheduler.scheduleWithFixedDelay(any(), 1000L, 1000L, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `Given a lease duration shorter than 2ms, Then the reap interval is clamped to at least 1ms`() {
        // Given
        every { config.leaseDuration() } returns Duration.ZERO
        // When
        lifecycle().start()
        // Then
        verify { scheduler.scheduleWithFixedDelay(any(), 1L, 1L, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `Given a poll failure, Then safePoll swallows it`() {
        // Given
        every { dispatcher.pollOnce() } throws RuntimeException("boom")
        // When / Then (no exception escapes)
        lifecycle().safePoll()
        verify { dispatcher.pollOnce() }
    }

    @Test
    fun `Given a clean poll, Then safePoll delegates once`() {
        // Given
        every { dispatcher.pollOnce() } returns Unit
        // When
        lifecycle().safePoll()
        // Then
        verify(exactly = 1) { dispatcher.pollOnce() }
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
        every { reap.reap() } returns 0
        // When
        lifecycle().safeReap()
        // Then
        verify(exactly = 1) { reap.reap() }
    }

    @Test
    fun `Given shutdown that drains in time, Then it stops claiming and does not warn`() {
        // Given
        every { executor.shutdownAndDrain(any()) } returns true
        // When
        lifecycle().stop()
        // Then
        verify { dispatcher.stopClaiming() }
        verify { scheduler.shutdown() }
    }

    @Test
    fun `Given shutdown that does not drain, Then the warn branch is taken`() {
        // Given
        every { executor.shutdownAndDrain(any()) } returns false
        // When / Then (covers the if-!drained branch)
        lifecycle().stop()
        verify { executor.shutdownAndDrain(Duration.ofSeconds(5)) }
    }

    @Test
    fun `Given the CDI startup event, Then onStart delegates to start`() {
        // Given
        every { config.leaseDuration() } returns Duration.ofSeconds(2)
        // When
        lifecycle().onStart(mockk<StartupEvent>())
        // Then
        verify { reap.reap() }
    }

    @Test
    fun `Given the CDI shutdown event, Then onStop delegates to stop`() {
        // Given
        every { executor.shutdownAndDrain(any()) } returns true
        // When
        lifecycle().onStop(mockk<ShutdownEvent>())
        // Then
        verify { dispatcher.stopClaiming() }
    }
}
