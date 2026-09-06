package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ReapExpiredTasksTest {
    private val taskQueue: TaskQueueInterface = mockk()
    private val clock: Clock = mockk()
    private val now = Instant.parse("2026-07-08T10:00:00Z")
    private val importFloor = Duration.ofMinutes(10)
    private val useCase = ReapExpiredTasks(taskQueue, registry(), clock)

    private fun registry() = TaskHandlerRegistry(
        listOf(
            flooredHandler(UserDataImportTask.KIND, importFloor),
            flooredHandler(PinDownloadTask.KIND, Duration.ZERO),
        ),
    )

    private fun flooredHandler(k: String, floor: Duration) = object : TaskHandler {
        override val kind = k
        override val retryFloor = floor
        override fun handle(payload: String, context: TaskContext) = Unit
    }

    @Test
    fun `Given expired running tasks, Then reap delegates to reapExpired with clock now`() {
        // Given
        every { clock.now() } returns now
        every { taskQueue.reapExpired(now, any(), ReapExpiredTasks.REAP_BATCH_SIZE) } returns 3

        // When
        val result = useCase.reap()

        // Then
        assertEquals(3, result)
        verify { taskQueue.reapExpired(now, any(), ReapExpiredTasks.REAP_BATCH_SIZE) }
    }

    @Test
    fun `Given no expired tasks, Then reap returns zero`() {
        // Given
        every { clock.now() } returns now
        every { taskQueue.reapExpired(now, any(), ReapExpiredTasks.REAP_BATCH_SIZE) } returns 0

        // When
        val result = useCase.reap()

        // Then
        assertEquals(0, result)
        verify { taskQueue.reapExpired(now, any(), ReapExpiredTasks.REAP_BATCH_SIZE) }
    }

    @Test
    fun `Given a kind whose handler declares a floor, Then reap hands the queue that floor`() {
        // Given: the reaper works on rows and cannot see a handler, so the floor of every registered
        // kind travels with the sweep (spec 2026-08-14-user-data-import.md section 9)
        every { clock.now() } returns now
        val floors = slot<Map<String, Duration>>()
        every { taskQueue.reapExpired(now, capture(floors), any()) } returns 1

        // When
        useCase.reap()

        // Then: exactly the registered kinds, so a kind left by a removed handler is absent rather
        // than floored at some default, and a kind declaring nothing keeps the queue's own window
        assertEquals(
            mapOf(UserDataImportTask.KIND to importFloor, PinDownloadTask.KIND to Duration.ZERO),
            floors.captured,
        )
    }
}
