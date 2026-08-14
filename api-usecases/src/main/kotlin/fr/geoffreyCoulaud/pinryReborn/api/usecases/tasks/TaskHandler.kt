package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import java.time.Duration

interface TaskHandler {
    val kind: String

    /** The shortest delay this kind accepts between two attempts; zero keeps the queue's own window. */
    val retryFloor: Duration get() = Duration.ZERO

    fun handle(payload: String, context: TaskContext)
}
