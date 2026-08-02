package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.exceptions

@Suppress("AbstractClassCanBeConcreteClass") // Abstract by intent: the base of the persistence exception hierarchy.
abstract class PersistenceException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
