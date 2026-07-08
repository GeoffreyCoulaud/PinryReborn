package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ClaimedTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskProcessor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID.randomUUID

class TaskDispatcherTest {
    private val queue: TaskQueueInterface = mockk()
    private val processor: TaskProcessor = mockk(relaxed = true)
    private val executor: WorkerExecutor = mockk()
    private val clock: Clock = mockk()
    private val config: TaskQueueConfig = mockk()
    private val now = Instant.parse("2026-07-08T00:00:00Z")

    private fun dispatcher() = TaskDispatcher(queue, processor, executor, clock, config)
    private fun claim() = ClaimedTask(randomUUID(), "k", "{}", 1, 3, "l", false)

    init {
        every { clock.now() } returns now
        every { config.leaseDuration() } returns Duration.ofMinutes(1)
    }

    @Test
    fun `Given two tasks and capacity, Then both are claimed and submitted`() {
        // Given
        val a = claim(); val b = claim()
        every { queue.claimNext(now, any()) } returnsMany listOf(a, b, null)
        every { executor.trySubmit(any()) } answers { firstArg<Runnable>().run(); true }
        // When
        dispatcher().pollOnce()
        // Then
        verify { processor.execute(a) }
        verify { processor.execute(b) }
    }

    @Test
    fun `Given no capacity, Then it stops after the first claim`() {
        // Given
        every { queue.claimNext(now, any()) } returns claim()
        every { executor.trySubmit(any()) } returns false
        // When
        dispatcher().pollOnce()
        // Then
        verify(exactly = 1) { queue.claimNext(now, any()) }
    }

    @Test
    fun `Given draining, Then no task is claimed`() {
        // Given
        val d = dispatcher()
        d.stopClaiming()
        // When
        d.pollOnce()
        // Then
        verify(exactly = 0) { queue.claimNext(any(), any()) }
    }
}
