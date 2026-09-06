package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StorageLayout
import java.util.UUID

/**
 * Where an import's promoted archive lives, derived from the id rather than read from the row, so
 * bytes a completer promoted before dying are still named by whoever has to reclaim them.
 */
object ImportArchiveKey {
    fun forImport(importId: UUID): String = "${StorageLayout.IMPORTS_DIRECTORY}/$importId.zip"
}
