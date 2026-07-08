package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.NewTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.Task
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.util.UUID

@ApplicationScoped
class EnqueueTask(private val taskQueue: TaskQueueInterface, private val clock: Clock) {
    @Suppress("LongParameterList")
    fun enqueue(
        kind: String,
        payload: String,
        maxAttempts: Int,
        delay: Duration = Duration.ZERO,
        priority: Int = 0,
        dedupKey: String? = null,
    ): Task = taskQueue.enqueue(
        NewTask(kind, payload, clock.now().plus(delay), priority, maxAttempts, dedupKey)
    )
}
