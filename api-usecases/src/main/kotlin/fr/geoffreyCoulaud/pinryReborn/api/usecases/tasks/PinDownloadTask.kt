package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

/** Identity + retry budget for the server-side image download task (mode B). */
object PinDownloadTask {
    const val KIND = "pin.download"
    const val MAX_ATTEMPTS = 5
}
