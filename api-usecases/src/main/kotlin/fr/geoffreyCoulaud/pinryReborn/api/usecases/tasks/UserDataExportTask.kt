package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

/** Identity + retry budget for the async user-data-export archive build task. */
object UserDataExportTask {
    const val KIND = "account.export"
    const val MAX_ATTEMPTS = 3
}
