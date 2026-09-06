package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StorageLayout
import java.util.UUID

/**
 * Where an export's promoted archive lives. The key is knowable without reading the row, which is
 * what lets the account cleaner and the orphan sweep name bytes no row still speaks for.
 */
object ExportArchiveKey {
    fun forExport(exportId: UUID, fileExtension: String): String =
        "${StorageLayout.EXPORTS_DIRECTORY}/$exportId.$fileExtension"
}
