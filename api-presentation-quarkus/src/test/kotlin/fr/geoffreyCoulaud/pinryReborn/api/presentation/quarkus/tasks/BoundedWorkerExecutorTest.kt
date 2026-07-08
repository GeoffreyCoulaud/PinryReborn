package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
    fun `Given free capacity, Then tryAcquire returns true and reserves the permit`() {
        // Given
        val permits = Semaphore(1)
        val exec = BoundedWorkerExecutor(permits, InlineExecutor(true))
        // When
        val acquired = exec.tryAcquire()
        // Then
        assertTrue(acquired)
        assertEquals(0, permits.availablePermits())
    }

    @Test
    fun `Given no capacity, Then tryAcquire returns false`() {
        // Given
        val permits = Semaphore(1)
        permits.acquire() // exhaust
        val exec = BoundedWorkerExecutor(permits, InlineExecutor(true))
        // When
        val acquired = exec.tryAcquire()
        // Then
        assertFalse(acquired)
    }

    @Test
    fun `Given a reserved permit, Then submit runs the job and releases the permit`() {
        // Given
        val permits = Semaphore(1)
        permits.acquire() // simulate a permit already reserved via tryAcquire()
        val exec = BoundedWorkerExecutor(permits, InlineExecutor(true))
        var ran = false
        // When
        exec.submit { ran = true }
        // Then
        assertTrue(ran)
        assertEquals(1, permits.availablePermits()) // released
    }

    @Test
    fun `Given a job that throws, Then submit still releases the permit`() {
        // Given
        val permits = Semaphore(1)
        permits.acquire() // simulate a permit already reserved via tryAcquire()
        val exec = BoundedWorkerExecutor(permits, InlineExecutor(true))
        // When
        assertThrows<IllegalStateException> {
            exec.submit { throw IllegalStateException("boom") }
        }
        // Then
        assertEquals(1, permits.availablePermits()) // released despite the failure
    }

    @Test
    fun `Given a reserved but unused permit, Then release gives it back`() {
        // Given
        val permits = Semaphore(1)
        permits.acquire() // simulate a permit reserved via tryAcquire() but never submitted
        val exec = BoundedWorkerExecutor(permits, InlineExecutor(true))
        // When
        exec.release()
        // Then
        assertEquals(1, permits.availablePermits())
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
