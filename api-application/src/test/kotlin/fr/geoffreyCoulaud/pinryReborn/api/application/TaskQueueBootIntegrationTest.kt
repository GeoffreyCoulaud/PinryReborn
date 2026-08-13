package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.EnqueueTask
import io.ebean.DB
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Proves the whole runtime -- real Quarkus boot, real background poller/worker pool
 * ([fr.geoffreyCoulaud.pinryReborn.api.worker.TaskWorkerLifecycle]),
 * real SQLite -- processes a task end to end. Enqueues a task of an unknown kind (no
 * [fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskHandler] is registered for it in this
 * test app), so [fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskProcessor]'s no-handler
 * path settles it straight to DEAD. This exercises claim -> execute -> settle without needing a
 * test-only handler.
 */
@QuarkusTest
class TaskQueueBootIntegrationTest : IntegrationTest() {
    @Inject lateinit var enqueueTask: EnqueueTask

    @Inject lateinit var taskQueue: TaskQueueInterface

    /**
     * The suite declares `:memory:` and once ran on a file regardless (`docs/adr/0012`). The write goes
     * through an injected port so the asserted handle is provably the one the application uses.
     */
    @Test
    fun `Given a task written through the injected port, Then the handle that reads it is in memory`() {
        // Given: one row, written by the application through its own port
        enqueueTask.enqueue(kind = "no.handler", payload = "{}", maxAttempts = 1)

        // When: the handle under assertion reads the tasks table
        val database = DB.getDefault()
        val taskCount = database
            .sqlQuery("select count(*) as task_count from tasks")
            .findOne()
            ?.getInteger("task_count")

        // Then: that handle sees the write, and has no file behind it
        assertEquals(1, taskCount, "Expected the handle to read the row the port wrote")
        val attached = database
            .sqlQuery("select name, file from pragma_database_list")
            .findList()
            .associate { it.getString("name") to it.getString("file") }
        // Positive anchor first: a filter over an empty list satisfies any "none of them" assertion.
        assertTrue(attached.containsKey("main"), "Expected a main database; pragma_database_list reports $attached")
        val backedByAFile = attached.filterValues { it.isNotEmpty() }
        assertEquals(
            emptyMap<String, String>(),
            backedByAFile,
            "Expected no database on this connection to have a file behind it; pragma_database_list reports $attached",
        )
    }

    @Test
    fun `Given an enqueued task with no handler, Then the runtime settles it to DEAD`() {
        // Given
        val task = enqueueTask.enqueue(kind = "no.handler", payload = "{}", maxAttempts = 1)

        // When: the background poller claims and processes it (no handler -> DEAD)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var state = taskQueue.findById(task.id)?.state
        while (state != TaskState.DEAD && System.nanoTime() < deadline) {
            Thread.sleep(50)
            state = taskQueue.findById(task.id)?.state
        }

        // Then
        assertEquals(TaskState.DEAD, state)
    }
}
