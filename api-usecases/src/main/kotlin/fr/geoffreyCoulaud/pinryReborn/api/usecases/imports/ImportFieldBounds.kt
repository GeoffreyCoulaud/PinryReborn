package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

/**
 * Spec section 4.1's bounds, restated because the import is a second write path into tables whose only
 * invariants live on REST input DTOs. Each answers null when the field passes, else the reported reason.
 */
object ImportFieldBounds {
    const val MAX_NAME_LENGTH = 200
    const val MAX_DESCRIPTION_LENGTH = 2000
    const val MAX_REFERENCES = 100

    /** Spec section 4: anchored, so `elsewhere/images/x.png` is not a match, and never a `.` segment. */
    private val ENTRY_PATH = Regex("^images/[A-Za-z0-9._-]+$")
    private val TRAVERSAL_SEGMENTS = setOf(".", "..")

    fun nameFault(name: String): String? =
        when {
            name.isBlank() -> "name is blank"
            name.length > MAX_NAME_LENGTH -> "name is longer than $MAX_NAME_LENGTH characters"
            else -> null
        }

    fun descriptionFault(description: String): String? =
        if (description.length > MAX_DESCRIPTION_LENGTH) {
            "description is longer than $MAX_DESCRIPTION_LENGTH characters"
        } else {
            null
        }

    fun blankFault(field: String, value: String): String? = if (value.isBlank()) "$field is blank" else null

    /** The list is resolved inside one transaction, so its length is that transaction's size. */
    fun referenceCountFault(field: String, count: Int): String? =
        if (count > MAX_REFERENCES) "$field holds more than $MAX_REFERENCES entries" else null

    /**
     * Traversal cannot reach the disk anyway: an entry name is only ever a ZIP lookup key. The check
     * exists so a malformed archive is reported rather than silently skipped.
     */
    fun entryPathFault(path: String): String? =
        when {
            !ENTRY_PATH.matches(path) -> "path is not an anchored images/<name>"
            path.substringAfterLast('/') in TRAVERSAL_SEGMENTS -> "path ends in a traversal segment"
            else -> null
        }
}
