package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

/** Per-attempt context handed to a [TaskHandler]: the current attempt number and the task's budget. */
data class TaskContext(val attempt: Int, val maxAttempts: Int)
