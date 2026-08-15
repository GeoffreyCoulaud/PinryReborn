package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import jakarta.enterprise.context.ApplicationScoped

/**
 * Reclaims expired leases. Sits between the registry and the queue because this is where a kind
 * resolves to its handler, and so to the floor its retries need: the queue works on rows.
 */
@ApplicationScoped
class ReapExpiredTasks(
    private val taskQueue: TaskQueueInterface,
    private val registry: TaskHandlerRegistry,
    private val clock: Clock,
) {
    fun reap(): Int = taskQueue.reapExpired(clock.now(), registry.retryFloors())
}
