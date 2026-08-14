package fr.geoffreyCoulaud.pinryReborn.api.domain.imports

/**
 * Raised when a chunk would carry the archive past [maxTotalBytes]. Only the store can see it, since a
 * chunk arrives as a stream of unannounced length.
 */
class ImportArchiveTooLargeException(val maxTotalBytes: Long) :
    Exception("Archive would grow past the $maxTotalBytes bytes this instance accepts")
