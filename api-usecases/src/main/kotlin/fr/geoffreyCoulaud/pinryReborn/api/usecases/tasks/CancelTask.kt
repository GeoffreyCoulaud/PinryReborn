package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class CancelTask(
    private val taskQueue: TaskQueueInterface,
    private val clock: Clock,
) {
    fun cancel(id: UUID): Boolean = taskQueue.cancelPending(id, clock.now()) || taskQueue.requestCancel(id)
}
