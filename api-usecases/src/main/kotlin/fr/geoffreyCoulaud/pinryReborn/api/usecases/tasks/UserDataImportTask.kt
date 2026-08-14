package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

/** Identity, retry budget and priority for the async user-data-import walk. */
object UserDataImportTask {
    const val KIND = "account.import"

    // Five, not the export's three: a disk-full walk is retried with the cursor resuming, and at the
    // queue's default backoff three attempts are spent in about three seconds, which no operator can use.
    const val MAX_ATTEMPTS = 5

    // Every other kind runs at the default 0, `account.delete` included, so "below account deletion"
    // is only expressible as a negative number. This is the first call site to pass the field.
    const val PRIORITY = -1
}
