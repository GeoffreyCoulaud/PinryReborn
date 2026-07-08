package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class ReapExpiredTasks(private val taskQueue: TaskQueueInterface, private val clock: Clock) {
    fun reap(): Int = taskQueue.reapExpired(clock.now())
}
