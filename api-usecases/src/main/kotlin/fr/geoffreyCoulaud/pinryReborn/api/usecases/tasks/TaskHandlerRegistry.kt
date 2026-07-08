package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

class TaskHandlerRegistry(handlers: List<TaskHandler>) {
    private val byKind: Map<String, TaskHandler> = handlers.associateBy { it.kind }
    fun handlerFor(kind: String): TaskHandler? = byKind[kind]
}
