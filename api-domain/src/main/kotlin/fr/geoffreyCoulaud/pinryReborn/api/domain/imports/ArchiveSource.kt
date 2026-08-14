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
     * Loan a lazy line sequence for a JSON Lines entry, same contract as the export's sink: the
     * adapter owns the entry stream and closes it when [block] returns.
     *
     * Every line of the entry is yielded, a bad one carrying its failure, so the sequence ends only at
     * the end of the entry. A line of no bytes is the one exception: it holds no entry to report.
     */
    fun <T : Any> readJsonLines(name: String, type: Class<T>, block: (Sequence<ArchiveLine<T>>) -> Unit)

    /** The raw bytes of one entry, or null when absent. The caller closes the stream. */
    fun openEntry(name: String): InputStream?
}
