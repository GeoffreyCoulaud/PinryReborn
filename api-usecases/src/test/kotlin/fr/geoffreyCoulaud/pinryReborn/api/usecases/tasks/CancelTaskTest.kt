package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class CancelTaskTest {
    private val queue: TaskQueueInterface = mockk()
    private val useCase = CancelTask(queue)

    @Test
    fun `Given a pending task, Then cancel flips it and does not request cancel`() {
        // Given
        val id = randomUUID()
        every { queue.cancelPending(id) } returns true
        // When
        val result = useCase.cancel(id)
        // Then
        assertTrue(result)
        verify(exactly = 0) { queue.requestCancel(any()) }
    }

    @Test
    fun `Given a running task, Then cancel requests cancellation`() {
        // Given
        val id = randomUUID()
        every { queue.cancelPending(id) } returns false
        every { queue.requestCancel(id) } returns true
        // When
        val result = useCase.cancel(id)
        // Then
        assertTrue(result)
    }

    @Test
    fun `Given an unknown task, Then cancel returns false`() {
        // Given
        val id = randomUUID()
        every { queue.cancelPending(id) } returns false
        every { queue.requestCancel(id) } returns false
        // When / Then
        assertFalse(useCase.cancel(id))
    }
}
