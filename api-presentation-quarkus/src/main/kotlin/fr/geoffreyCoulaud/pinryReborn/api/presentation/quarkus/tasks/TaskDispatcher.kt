package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskProcessor
import jakarta.enterprise.context.ApplicationScoped

/**
 * Polls the task queue for claimable work and hands each claimed task off to the
 * [WorkerExecutor]. One [pollOnce] call performs a single tick: it claims and submits
 * tasks until either the queue is empty, the worker pool is at capacity, or draining
 * has been requested via [stopClaiming].
 */
@ApplicationScoped
class TaskDispatcher(
    private val taskQueue: TaskQueueInterface,
    private val taskProcessor: TaskProcessor,
    private val workerExecutor: WorkerExecutor,
    private val clock: Clock,
    private val config: TaskQueueConfig,
) {
    @Volatile
    private var draining = false

    fun stopClaiming() {
        draining = true
    }

    @Suppress("LoopWithTooManyJumpStatements")
    fun pollOnce() {
        while (!draining) {
            val claimed = taskQueue.claimNext(clock.now(), config.leaseDuration()) ?: break
            val submitted = workerExecutor.trySubmit { taskProcessor.execute(claimed) }
            if (!submitted) break
        }
    }
}
