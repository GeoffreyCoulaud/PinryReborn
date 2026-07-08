package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.BackoffPolicy
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ExponentialBackoffWithJitter
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskHandler
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskHandlerRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.Produces
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
}
