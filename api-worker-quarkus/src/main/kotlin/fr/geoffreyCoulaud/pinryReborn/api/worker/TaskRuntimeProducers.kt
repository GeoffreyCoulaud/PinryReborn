package fr.geoffreyCoulaud.pinryReborn.api.worker

import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.BackoffPolicy
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ExponentialBackoffWithJitter
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskHandler
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskHandlerRegistry
import io.smallrye.common.annotation.Identifier
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.Produces
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadLocalRandom

@ApplicationScoped
class TaskRuntimeProducers {
    @Produces
    @ApplicationScoped
    fun backoffPolicy(config: TaskQueueConfig): BackoffPolicy =
        ExponentialBackoffWithJitter(config.backoffBase(), config.backoffCap()) {
            ThreadLocalRandom.current().nextDouble()
        }

    @Produces
    @ApplicationScoped
    fun taskHandlerRegistry(handlers: Instance<TaskHandler>): TaskHandlerRegistry =
        TaskHandlerRegistry(handlers.stream().toList())

    @Produces
    @ApplicationScoped
    fun workerExecutor(config: TaskQueueConfig): WorkerExecutor =
        BoundedWorkerExecutor(Semaphore(config.workerCount()), Executors.newFixedThreadPool(config.workerCount()))

    @Produces
    @ApplicationScoped
    @Identifier(TASK_POLL_SCHEDULER)
    fun pollScheduler(): ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
}
