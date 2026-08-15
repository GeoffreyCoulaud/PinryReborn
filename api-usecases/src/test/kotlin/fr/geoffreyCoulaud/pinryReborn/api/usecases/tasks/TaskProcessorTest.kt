package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.BackoffPolicy
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ClaimedTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ExponentialBackoffWithJitter
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.exceptions.PermanentTaskException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID.randomUUID

class TaskProcessorTest {
    private val queue: TaskQueueInterface = mockk(relaxed = true)
    private val backoff: BackoffPolicy = mockk()
    private val clock: Clock = mockk()
    private val now = Instant.parse("2026-07-08T00:00:00Z")
    private val leaseDuration = Duration.ofMinutes(1)

    private fun processorWith(handler: TaskHandler?, policy: BackoffPolicy = backoff) =
        TaskProcessor(
            taskQueue = queue,
            registry = TaskHandlerRegistry(listOfNotNull(handler)),
            backoffPolicy = policy,
            clock = clock,
        )

    private fun claimed(kind: String = "k", attempts: Int = 1, maxAttempts: Int = 3, cancelRequested: Boolean = false) =
        ClaimedTask(randomUUID(), kind, "{}", attempts, maxAttempts, "lease-1", cancelRequested)

    private fun handler(kind: String, body: () -> Unit) = object : TaskHandler {
        override val kind = kind
        override fun handle(payload: String, context: TaskContext) = body()
    }

    private fun flooredHandler(kind: String, floor: Duration, body: () -> Unit) = object : TaskHandler {
        override val kind = kind
        override val retryFloor = floor
        override fun handle(payload: String, context: TaskContext) = body()
    }

    @Test
    fun `Given a successful handler, Then the task is marked succeeded`() {
        // Given
        every { clock.now() } returns now
        val c = claimed()
        val p = processorWith(handler("k") { })
        // When
        p.execute(c, leaseDuration)
        // Then
        verify { queue.markSucceeded(c.id, "lease-1", now) }
    }

    @Test
    fun `Given no handler for the kind, Then the task is marked dead`() {
        // Given
        every { clock.now() } returns now
        val c = claimed(kind = "unknown")
        val p = processorWith(null)
        // When
        p.execute(c, leaseDuration)
        // Then
        verify { queue.markDead(c.id, "lease-1", now, "no handler for kind unknown") }
    }

    @Test
    fun `Given cancel requested at claim, Then the task is cancelled without running`() {
        // Given
        every { clock.now() } returns now
        var ran = false
        val c = claimed(cancelRequested = true)
        val p = processorWith(handler("k") { ran = true })
        // When
        p.execute(c, leaseDuration)
        // Then
        verify { queue.markCancelledIfRequested(c.id, "lease-1", now) }
        assert(!ran)
    }

    @Test
    fun `Given cancel requested during execution, Then it is honored and settle is skipped`() {
        // Given
        every { clock.now() } returns now
        every { queue.markCancelledIfRequested(any(), any(), any()) } returns true
        val c = claimed()
        val p = processorWith(handler("k") { })
        // When
        p.execute(c, leaseDuration)
        // Then
        verify(exactly = 0) { queue.markSucceeded(any(), any(), any()) }
    }

    @Test
    fun `Given a retryable failure below the attempt limit, Then it is rescheduled with backoff`() {
        // Given
        every { clock.now() } returns now
        every { queue.markCancelledIfRequested(any(), any(), any()) } returns false
        val retryAt = now.plusSeconds(4)
        every { backoff.nextAttemptAt(2, now, Duration.ZERO) } returns retryAt
        val c = claimed(attempts = 2, maxAttempts = 3)
        val p = processorWith(handler("k") { throw IllegalStateException("boom") })
        // When
        p.execute(c, leaseDuration)
        // Then
        verify { queue.markPendingRetry(c.id, "lease-1", retryAt, now, "boom") }
    }

    @Test
    fun `Given a retryable failure at the attempt limit, Then it is marked dead`() {
        // Given
        every { clock.now() } returns now
        every { queue.markCancelledIfRequested(any(), any(), any()) } returns false
        val c = claimed(attempts = 3, maxAttempts = 3)
        val p = processorWith(handler("k") { throw IllegalStateException("boom") })
        // When
        p.execute(c, leaseDuration)
        // Then
        verify { queue.markDead(c.id, "lease-1", now, "boom") }
    }

    @Test
    fun `Given a permanent failure, Then it is marked dead without retry`() {
        // Given
        every { clock.now() } returns now
        every { queue.markCancelledIfRequested(any(), any(), any()) } returns false
        val c = claimed(attempts = 1, maxAttempts = 3)
        val p = processorWith(handler("k") { throw PermanentTaskException("nope") })
        // When
        p.execute(c, leaseDuration)
        // Then
        verify { queue.markDead(c.id, "lease-1", now, "nope") }
    }

    @Test
    fun `Given a retryable failure with no message, Then a default message is used`() {
        // Given
        every { clock.now() } returns now
        every { queue.markCancelledIfRequested(any(), any(), any()) } returns false
        every { backoff.nextAttemptAt(1, now, Duration.ZERO) } returns now.plusSeconds(1)
        val c = claimed(attempts = 1, maxAttempts = 3)
        val p = processorWith(handler("k") { throw IllegalStateException() })
        // When
        p.execute(c, leaseDuration)
        // Then
        verify { queue.markPendingRetry(c.id, "lease-1", now.plusSeconds(1), now, "transient failure") }
    }

    @Test
    fun `Given a handler, Then it receives the claim's attempt and maxAttempts`() {
        // Given
        every { clock.now() } returns now
        var seen: TaskContext? = null
        val c = claimed(attempts = 2, maxAttempts = 3)
        val p = processorWith(object : TaskHandler {
            override val kind = "k"
            override fun handle(payload: String, context: TaskContext) { seen = context }
        })
        // When
        p.execute(c, leaseDuration)
        // Then
        assertEquals(2, seen?.attempt)
        assertEquals(3, seen?.maxAttempts)
    }

    @Test
    fun `Given attempt 2 of each kind, Then the import waits out its floor and the download does not`() {
        // Given: the real policy at full jitter, so both delays are computed rather than stubbed. The
        // unfloored window is base * 2 = 4s, which is what an operator cannot use (spec section 9).
        every { clock.now() } returns now
        every { queue.markCancelledIfRequested(any(), any(), any()) } returns false
        val retryAts = mutableListOf<Instant>()
        every { queue.markPendingRetry(any(), any(), capture(retryAts), any(), any()) } returns true
        val policy = ExponentialBackoffWithJitter(base = Duration.ofSeconds(2), cap = Duration.ofMinutes(1)) { 1.0 }
        val floor = Duration.ofMinutes(10)
        val boom = { throw IllegalStateException("boom") }

        // When
        processorWith(flooredHandler(UserDataImportTask.KIND, floor, boom), policy)
            .execute(claimed(kind = UserDataImportTask.KIND, attempts = 2), leaseDuration)
        processorWith(handler(PinDownloadTask.KIND, boom), policy)
            .execute(claimed(kind = PinDownloadTask.KIND, attempts = 2), leaseDuration)

        // Then
        // The clock is stubbed and the jitter is full, so both instants are exact: at or above the
        // floor would pass a policy adding the floor to the window rather than flooring it.
        val (importRetryAt, downloadRetryAt) = retryAts
        assertEquals(now.plus(floor), importRetryAt, "the import waits out its floor, and no longer")
        assertEquals(now.plusSeconds(4), downloadRetryAt, "a kind declaring no floor keeps its window")
    }

    @Test
    fun `Given a handler that heartbeats, Then its lease is renewed by one duration from now`() {
        // Given
        every { clock.now() } returns now
        val c = claimed()
        val p = processorWith(object : TaskHandler {
            override val kind = "k"
            override fun handle(payload: String, context: TaskContext) { context.renewLease() }
        })
        // When
        p.execute(c, Duration.ofMinutes(2))
        // Then
        verify { queue.renewLease(c.id, "lease-1", now.plus(Duration.ofMinutes(2))) }
    }
}
