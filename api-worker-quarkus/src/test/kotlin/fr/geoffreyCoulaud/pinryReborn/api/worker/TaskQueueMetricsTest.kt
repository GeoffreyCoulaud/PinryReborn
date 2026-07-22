package fr.geoffreyCoulaud.pinryReborn.api.worker

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TaskQueueMetricsTest {
    private val taskQueue: TaskQueueInterface = mockk()
    private val registry: MeterRegistry = SimpleMeterRegistry()

    private fun metrics() = TaskQueueMetrics(taskQueue, registry)

    @Test
    fun `Given registered gauges, Then each gauge value reflects the current count by state`() {
        // Given
        every { taskQueue.countByState(TaskState.PENDING) } returns 3
        every { taskQueue.countByState(TaskState.RUNNING) } returns 5
        every { taskQueue.countByState(TaskState.DEAD) } returns 7

        // When
        metrics().onStart(mockk<StartupEvent>())

        // Then
        assertEquals(3.0, registry.get("tasks.pending").gauge().value())
        assertEquals(5.0, registry.get("tasks.running").gauge().value())
        assertEquals(7.0, registry.get("tasks.dead").gauge().value())
    }
}
