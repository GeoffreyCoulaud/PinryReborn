package fr.geoffreyCoulaud.pinryReborn.api.domain.imports

/**
 * Raised when an archive read would go past the bound it was given. A type of its own, not an
 * `IOException`: a refused read and a read that reached corrupt bytes are two different answers.
 */
class ArchiveBoundExceededException(message: String) : Exception(message)
