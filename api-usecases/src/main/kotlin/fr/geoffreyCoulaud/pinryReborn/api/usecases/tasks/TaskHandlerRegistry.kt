package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import java.time.Duration

class TaskHandlerRegistry(handlers: List<TaskHandler>) {
    private val byKind: Map<String, TaskHandler> = handlers.associateBy { it.kind }

    fun handlerFor(kind: String): TaskHandler? = byKind[kind]

    /** Read per call, not cached, so it reaches the sweep the way [TaskHandler.retryFloor] reaches a settle. */
    fun retryFloors(): Map<String, Duration> = byKind.mapValues { (_, handler) -> handler.retryFloor }
}
