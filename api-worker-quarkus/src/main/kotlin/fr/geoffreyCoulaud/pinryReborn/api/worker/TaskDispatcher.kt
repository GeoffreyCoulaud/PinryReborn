package fr.geoffreyCoulaud.pinryReborn.api.worker

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskProcessor
import jakarta.enterprise.context.ApplicationScoped

/**
 * Polls the task queue for claimable work and hands each claimed task off to the
 * [WorkerExecutor]. One [pollOnce] call performs a single tick: it reserves a worker
 * slot *before* claiming ([WorkerExecutor.tryAcquire]) so a claimed task (already
 * flipped to RUNNING with a lease) is never left without a worker to run it. It stops
 * claiming once either the worker pool is at capacity, the queue is empty (giving back
 * the reserved slot via [WorkerExecutor.release]), or draining has been requested via
 * [stopClaiming]. If claiming itself throws (e.g. a persistence error under write
 * contention), the reserved slot is released before the exception propagates, so a
 * failed claim never leaks a permit.
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

    @Suppress("LoopWithTooManyJumpStatements", "TooGenericExceptionCaught")
    fun pollOnce() {
        while (!draining) {
            if (!workerExecutor.tryAcquire()) break
            val claimed =
                try {
                    taskQueue.claimNext(clock.now(), config.leaseDuration())
                } catch (e: Exception) {
                    workerExecutor.release()
                    throw e
                }
            if (claimed == null) {
                workerExecutor.release()
                break
            }
            workerExecutor.submit { taskProcessor.execute(claimed) }
        }
    }
}
