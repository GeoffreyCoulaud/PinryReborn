package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ReapTerminalTasksTest : BaseTest() {
    private val taskQueue: TaskQueueInterface = mockk()
    private val clock: Clock = mockk()
    private val terminalTaskGrace = Duration.ofDays(7)

    private val reap = ReapTerminalTasks(
        taskQueue = taskQueue,
        clock = clock,
        terminalTaskGrace = terminalTaskGrace,
    )

    private val now = Instant.parse("2026-07-27T00:00:00Z")
    private val cutoff = now.minus(terminalTaskGrace)

    @Test
    fun `Given terminal tasks older than grace, Then reap delegates to deleteTerminalBefore with the cutoff and returns the count`() {
        // Given
        every { clock.now() } returns now
        every { taskQueue.deleteTerminalBefore(cutoff) } returns 5

        // When
        val count = reap.reap()

        // Then: the cutoff is clock.now() minus the grace, and the repository count is returned
        assertEquals(5, count)
        verify { taskQueue.deleteTerminalBefore(cutoff) }
    }

    @Test
    fun `Given no terminal task past grace, Then reap returns zero`() {
        // Given
        every { clock.now() } returns now
        every { taskQueue.deleteTerminalBefore(cutoff) } returns 0

        // When
        val count = reap.reap()

        // Then
        assertEquals(0, count)
        verify { taskQueue.deleteTerminalBefore(cutoff) }
    }
}
