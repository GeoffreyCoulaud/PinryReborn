package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ClaimedTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskProcessor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
    fun `Given capacity, Then it acquires a permit, claims a task, and submits it`() {
        // Given
        val a = claim()
        every { executor.tryAcquire() } returnsMany listOf(true, false)
        every { queue.claimNext(now, any()) } returns a
        every { executor.submit(any()) } answers { firstArg<Runnable>().run() }
        // When
        dispatcher().pollOnce()
        // Then
        verify(exactly = 1) { queue.claimNext(now, any()) }
        verify { executor.submit(any()) }
        verify { processor.execute(a) }
    }

    @Test
    fun `Given no capacity, Then it stops without claiming any task`() {
        // Given
        every { executor.tryAcquire() } returns false
        // When
        dispatcher().pollOnce()
        // Then
        verify(exactly = 0) { queue.claimNext(any(), any()) }
    }

    @Test
    fun `Given capacity but nothing to claim, Then it releases the permit and stops`() {
        // Given
        every { executor.tryAcquire() } returns true
        every { queue.claimNext(now, any()) } returns null
        every { executor.release() } returns Unit
        // When
        dispatcher().pollOnce()
        // Then
        verify(exactly = 1) { queue.claimNext(now, any()) }
        verify { executor.release() }
        verify(exactly = 0) { executor.submit(any()) }
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

    @Test
    fun `Given claimNext throws, Then the acquired permit is released`() {
        // Given
        every { executor.tryAcquire() } returns true
        every { queue.claimNext(any(), any()) } throws IllegalStateException("boom")
        every { executor.release() } returns Unit
        // When
        assertThrows<IllegalStateException> { dispatcher().pollOnce() }
        // Then
        verify(exactly = 1) { executor.release() }
    }
}
