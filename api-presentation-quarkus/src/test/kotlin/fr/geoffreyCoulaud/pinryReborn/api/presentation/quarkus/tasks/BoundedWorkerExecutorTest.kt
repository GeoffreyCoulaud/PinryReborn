package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class BoundedWorkerExecutorTest {
    // Minimal inline ExecutorService: runs submitted work immediately, records shutdown/awaitTermination.
    private class InlineExecutor(private val awaitResult: Boolean) : AbstractExecutorService() {
        var shutdownCalled = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { shutdownCalled = true }
        override fun shutdownNow() = mutableListOf<Runnable>()
        override fun isShutdown() = shutdownCalled
        override fun isTerminated() = shutdownCalled
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = awaitResult
    }

    @Test
    fun `Given free capacity, Then trySubmit runs the job and releases the permit`() {
        // Given
        val permits = Semaphore(1)
        val exec = BoundedWorkerExecutor(permits, InlineExecutor(true))
        var ran = false
        // When
        val submitted = exec.trySubmit { ran = true }
        // Then
        assertTrue(submitted)
        assertTrue(ran)
        assertEquals(1, permits.availablePermits()) // released
    }

    @Test
    fun `Given no capacity, Then trySubmit returns false and does not run`() {
        // Given
        val permits = Semaphore(1)
        permits.acquire() // exhaust
        val exec = BoundedWorkerExecutor(permits, InlineExecutor(true))
        var ran = false
        // When
        val submitted = exec.trySubmit { ran = true }
        // Then
        assertFalse(submitted)
        assertFalse(ran)
    }

    @Test
    fun `Given the pool drains in time, Then shutdownAndDrain returns true`() {
        val exec = BoundedWorkerExecutor(Semaphore(1), InlineExecutor(awaitResult = true))
        assertTrue(exec.shutdownAndDrain(Duration.ofSeconds(1)))
    }

    @Test
    fun `Given the pool does not drain in time, Then shutdownAndDrain returns false`() {
        val exec = BoundedWorkerExecutor(Semaphore(1), InlineExecutor(awaitResult = false))
        assertFalse(exec.shutdownAndDrain(Duration.ofSeconds(1)))
    }
}
