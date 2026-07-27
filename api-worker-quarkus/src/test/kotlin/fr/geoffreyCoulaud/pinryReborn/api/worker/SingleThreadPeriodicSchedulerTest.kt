package fr.geoffreyCoulaud.pinryReborn.api.worker

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class SingleThreadPeriodicSchedulerTest {
    @Test
    fun `Given a scheduled task, Then it runs on the executor thread`() {
        // Given
        val scheduler = SingleThreadPeriodicScheduler()
        val latch = CountDownLatch(1)

        // When
        scheduler.scheduleWithFixedDelay({ latch.countDown() }, 0L, 1L, TimeUnit.SECONDS)

        // Then
        val reachedZero = latch.await(2L, TimeUnit.SECONDS)
        scheduler.shutdown()
        assert(reachedZero)
    }

    @Test
    fun `Given shutdown, Then further scheduling is rejected`() {
        // Given
        val scheduler = SingleThreadPeriodicScheduler()
        scheduler.shutdown()

        // When / Then
        assertThrows<RejectedExecutionException> {
            scheduler.scheduleWithFixedDelay({ }, 0L, 1L, TimeUnit.SECONDS)
        }
    }
}
