package fr.geoffreyCoulaud.pinryReborn.api.domain.imports

/**
 * Raised when one entry cannot be read: a truncated or bit-rotted stream, reached while the bytes are
 * pulled. Per entry, so a caller settles the line it is on; its own I/O failures keep their type.
 */
class ArchiveEntryUnreadableException(message: String, cause: Throwable? = null) : Exception(message, cause)
