package fr.geoffreyCoulaud.pinryReborn.api.domain.imports

/**
 * Raised when a chunk's offset is not what the store already holds. [currentLength] is read from disk,
 * which is the authority a resuming client needs when the row and the bytes have drifted apart.
 */
class ImportChunkOffsetMismatchException(val currentLength: Long) :
    Exception("Chunk offset does not match the current length of $currentLength")
