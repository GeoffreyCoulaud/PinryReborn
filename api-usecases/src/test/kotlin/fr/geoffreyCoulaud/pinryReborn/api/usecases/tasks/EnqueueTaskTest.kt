package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.NewTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.Task
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID.randomUUID

class EnqueueTaskTest {
    private val taskQueue: TaskQueueInterface = mockk()
    private val clock: Clock = mockk()
    private val now = Instant.parse("2026-07-08T10:00:00Z")
    private val useCase = EnqueueTask(taskQueue, clock)

    @Test
    fun `Given a valid task, Then enqueue builds NewTask with availableAt equals now plus delay`() {
        // Given
        val kind = "send-email"
        val payload = "{\"email\": \"test@example.com\"}"
        val maxAttempts = 3
        val delay = Duration.ofSeconds(30)
        val priority = 1
        val dedupKey = "unique-key"
        val expectedTask = Task(
            id = randomUUID(),
            kind = kind,
            payload = payload,
            state = TaskState.PENDING,
            priority = priority,
            availableAt = now.plus(delay),
            attempts = 0,
            maxAttempts = maxAttempts,
            leaseId = null,
            leaseExpiresAt = null,
            cancelRequested = false,
            dedupKey = dedupKey,
            lastError = null,
        )
        every { clock.now() } returns now
        val newTaskSlot = slot<NewTask>()
        every { taskQueue.enqueue(capture(newTaskSlot)) } returns expectedTask

        // When
        val result = useCase.enqueue(kind, payload, maxAttempts, delay, priority, dedupKey)

        // Then
        assertEquals(expectedTask, result)
        assertEquals(kind, newTaskSlot.captured.kind)
        assertEquals(payload, newTaskSlot.captured.payload)
        assertEquals(now.plus(delay), newTaskSlot.captured.availableAt)
        assertEquals(priority, newTaskSlot.captured.priority)
        assertEquals(maxAttempts, newTaskSlot.captured.maxAttempts)
        assertEquals(dedupKey, newTaskSlot.captured.dedupKey)
    }

    @Test
    fun `Given no delay specified, Then enqueue uses availableAt equals now`() {
        // Given
        val kind = "process-image"
        val payload = "{\"url\": \"https://example.com/image.jpg\"}"
        val maxAttempts = 2
        val expectedTask = Task(
            id = randomUUID(),
            kind = kind,
            payload = payload,
            state = TaskState.PENDING,
            priority = 0,
            availableAt = now,
            attempts = 0,
            maxAttempts = maxAttempts,
            leaseId = null,
            leaseExpiresAt = null,
            cancelRequested = false,
            dedupKey = null,
            lastError = null,
        )
        every { clock.now() } returns now
        val newTaskSlot = slot<NewTask>()
        every { taskQueue.enqueue(capture(newTaskSlot)) } returns expectedTask

        // When
        val result = useCase.enqueue(kind, payload, maxAttempts)

        // Then
        assertEquals(expectedTask, result)
        assertEquals(now, newTaskSlot.captured.availableAt)
        assertEquals(0, newTaskSlot.captured.priority)
        assertEquals(null, newTaskSlot.captured.dedupKey)
    }
}
