package fr.geoffreyCoulaud.pinryReborn.api.worker

import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.ReapExpiredTasks
import io.github.oshai.kotlinlogging.KotlinLogging
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import io.smallrye.common.annotation.Identifier
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * CDI qualifier identifier for the task queue's poll [ScheduledExecutorService]. Quarkus's own
 * ArC container always registers a synthetic `@Default` bean for the raw JDK
 * [ScheduledExecutorService]/`ExecutorService`/`Executor` types (backed by its main blocking
 * pool); without this qualifier, [TaskRuntimeProducers.pollScheduler] would be ambiguous with
 * that built-in bean as soon as anything actually injects [ScheduledExecutorService].
 */
internal const val TASK_POLL_SCHEDULER = "task-poll-scheduler"

/**
 * Drives the task worker lifecycle: sweeps orphaned leases and starts the poll loop on
 * application startup, keeps sweeping expired leases periodically (at half the lease
 * duration) so tasks stuck behind a crashed/hung worker are recovered at runtime rather
 * than only at the next boot, and stops claiming new work and drains in-flight workers
 * on shutdown.
 */
@ApplicationScoped
class TaskWorkerLifecycle(
    private val dispatcher: TaskDispatcher,
    private val reapExpiredTasks: ReapExpiredTasks,
    private val workerExecutor: WorkerExecutor,
    @Identifier(TASK_POLL_SCHEDULER) private val pollScheduler: ScheduledExecutorService,
    private val config: TaskQueueConfig,
) {
    fun onStart(
        @Observes ignored: StartupEvent,
    ) = start()

    fun onStop(
        @Observes ignored: ShutdownEvent,
    ) = stop()

    fun start() {
        reapExpiredTasks.reap()
        pollScheduler.scheduleWithFixedDelay(
            { safePoll() },
            0L,
            config.pollInterval().toMillis(),
            TimeUnit.MILLISECONDS,
        )
        val reapIntervalMs = (config.leaseDuration().toMillis() / 2).coerceAtLeast(1)
        pollScheduler.scheduleWithFixedDelay(
            { safeReap() },
            reapIntervalMs,
            reapIntervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    fun safePoll() {
        try {
            dispatcher.pollOnce()
        } catch (e: Exception) {
            logger.error(e) { "task poll failed" }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun safeReap() {
        try {
            reapExpiredTasks.reap()
        } catch (e: Exception) {
            logger.error(e) { "task reap failed" }
        }
    }

    fun stop() {
        dispatcher.stopClaiming()
        pollScheduler.shutdown()
        if (!workerExecutor.shutdownAndDrain(config.shutdownDrainTimeout())) {
            logger.warn { "task worker pool did not drain within the shutdown timeout" }
        }
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}
