package fr.geoffreyCoulaud.pinryReborn.api.worker

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class SingleThreadGarbageCollectionExecutorTest {
    @Test
    fun `Given a scheduleWithFixedDelay call, Then it delegates to the underlying executor with the same args`() {
        // Given
        val delegate = mockk<ScheduledExecutorService>(relaxed = true)
        val executor = SingleThreadGarbageCollectionExecutor(delegate)
        val command = Runnable { }

        // When
        executor.scheduleWithFixedDelay(command, 1L, 2L, TimeUnit.MILLISECONDS)

        // Then
        verify { delegate.scheduleWithFixedDelay(command, 1L, 2L, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `Given a shutdown call, Then it delegates to the underlying executor`() {
        // Given
        val delegate = mockk<ScheduledExecutorService>(relaxed = true)
        val executor = SingleThreadGarbageCollectionExecutor(delegate)

        // When
        executor.shutdown()

        // Then
        verify { delegate.shutdown() }
    }
}
