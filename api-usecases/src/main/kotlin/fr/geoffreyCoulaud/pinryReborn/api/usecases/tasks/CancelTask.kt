package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class CancelTask(private val taskQueue: TaskQueueInterface) {
    fun cancel(id: UUID): Boolean = taskQueue.cancelPending(id) || taskQueue.requestCancel(id)
}
