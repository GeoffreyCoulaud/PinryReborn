package fr.geoffreyCoulaud.pinryReborn.api.domain.imports

import java.io.InputStream

/**
 * Random access to one uploaded archive, opened by [ImportArchiveStore.open]. Every read is bounded,
 * because the archive is hostile input: the adapter refuses rather than allocating to exhaustion.
 */
interface ArchiveSource : AutoCloseable {
    /** Entry names, refusing an archive that declares more than [maxEntries]. */
    fun entryNames(maxEntries: Int): Set<String>

    /** The entry parsed as one JSON document, or null when absent; refuses past [maxBytes]. */
    fun <T : Any> readJson(name: String, type: Class<T>, maxBytes: Long): T?

    /**
     * Loan a lazy line sequence for a JSON Lines entry: the adapter owns the stream and closes it when
     * [block] returns. Every line but a blank one is yielded, so it ends at the end of the entry only.
     */
    fun <T : Any> readJsonLines(name: String, type: Class<T>, block: (Sequence<ArchiveLine<T>>) -> Unit)

    /** The raw bytes of one entry, or null when absent. The caller closes the stream. */
    fun openEntry(name: String): InputStream?
}
