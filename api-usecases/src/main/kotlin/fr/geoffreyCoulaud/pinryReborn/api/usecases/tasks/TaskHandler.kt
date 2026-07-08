package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

interface TaskHandler {
    val kind: String
    fun handle(payload: String)
}
