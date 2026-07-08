package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class ReapExpiredTasksTest {
    private val taskQueue: TaskQueueInterface = mockk()
    private val clock: Clock = mockk()
    private val now = Instant.parse("2026-07-08T10:00:00Z")
    private val useCase = ReapExpiredTasks(taskQueue, clock)

    @Test
    fun `Given expired running tasks, Then reap delegates to reapExpired with clock now`() {
        // Given
        every { clock.now() } returns now
        every { taskQueue.reapExpired(now) } returns 3

        // When
        val result = useCase.reap()

        // Then
        assertEquals(3, result)
        verify { taskQueue.reapExpired(now) }
    }

    @Test
    fun `Given no expired tasks, Then reap returns zero`() {
        // Given
        every { clock.now() } returns now
        every { taskQueue.reapExpired(now) } returns 0

        // When
        val result = useCase.reap()

        // Then
        assertEquals(0, result)
        verify { taskQueue.reapExpired(now) }
    }
}
