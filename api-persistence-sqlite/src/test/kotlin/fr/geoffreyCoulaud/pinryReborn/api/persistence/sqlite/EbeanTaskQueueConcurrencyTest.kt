package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ClaimedTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.NewTask
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.EbeanTaskQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

/**
 * Concurrency test for [EbeanTaskQueue.claimNext].
 *
 * The production and test datasources are both pinned to a SINGLE connection (option A), so claim
 * serialization comes from the connection pool itself rather than from any in-memory locking. This
 * test drives several threads at [EbeanTaskQueue.claimNext] concurrently to prove that guarantee:
 * every enqueued task is claimed exactly once, none lost, none double-claimed, and with ZERO
 * exceptions (in particular no [io.ebean.OptimisticLockException]): the atomic select+update
 * transaction in [EbeanTaskQueue.claimNext] serializes the claim on the single connection instead
 * of relying on optimistic-lock retries to paper over a lost race.
 */
class EbeanTaskQueueConcurrencyTest : RepositoryTest() {
    private val queue = EbeanTaskQueue(database)
    private val now = Instant.parse("2026-07-08T00:00:00Z")

    @Test
    fun `Given 200 pending tasks and 8 concurrent claimers, Then all are claimed once with no exceptions`() {
        // Given
        val taskCount = 200
        val threadCount = 8
        repeat(taskCount) {
            queue.enqueue(NewTask(kind = "k", payload = "{}", availableAt = now, maxAttempts = 1))
        }
        val claimed = ConcurrentLinkedQueue<ClaimedTask>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val startLatch = CountDownLatch(1)
        val threads =
            List(threadCount) {
                Thread {
                    startLatch.await()
                    while (true) {
                        val task = queue.claimNext(now, Duration.ofMinutes(1)) ?: break
                        claimed.add(task)
                    }
                }.apply {
                    setUncaughtExceptionHandler { _, throwable -> failures.add(throwable) }
                }
            }

        // When
        threads.forEach { it.start() }
        startLatch.countDown()
        threads.forEach { it.join() }

        // Then
        assertTrue(failures.isEmpty()) { "Expected zero exceptions while claiming concurrently, got: $failures" }
        assertEquals(taskCount, claimed.size)
        assertEquals(taskCount, claimed.map { it.id }.toSet().size)
    }
}
