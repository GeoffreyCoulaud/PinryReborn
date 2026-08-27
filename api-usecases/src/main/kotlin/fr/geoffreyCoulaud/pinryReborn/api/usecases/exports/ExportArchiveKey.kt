package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import java.util.UUID

/**
 * Where an export's promoted archive lives. The key is knowable without reading the row, which is
 * what lets the account cleaner and the orphan sweep name bytes no row still speaks for.
 */
object ExportArchiveKey {
    /** The directory half, so a startup check probes what a key really resolves under. */
    const val DIRECTORY = "exports"

    fun forExport(exportId: UUID, fileExtension: String): String = "$DIRECTORY/$exportId.$fileExtension"
}
