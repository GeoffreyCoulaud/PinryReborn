package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

/**
 * Spec section 4.1's bounds, restated because the import is a second write path into tables whose only
 * invariants live on REST input DTOs. Each answers null when the field passes, else the reported reason.
 */
object ImportFieldBounds {
    const val MAX_NAME_LENGTH = 200
    const val MAX_DESCRIPTION_LENGTH = 2000

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
}
