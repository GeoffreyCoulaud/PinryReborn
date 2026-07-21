package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

/** Identity + retry budget for the async account-erasure task run after a `DELETE /me` tombstone. */
object AccountDeletionTask {
    const val KIND = "account.delete"
    const val MAX_ATTEMPTS = 5
}
