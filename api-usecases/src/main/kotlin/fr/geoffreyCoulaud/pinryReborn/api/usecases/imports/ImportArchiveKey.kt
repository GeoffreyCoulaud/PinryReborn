package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import java.util.UUID

/**
 * Where an import's promoted archive lives, derived from the id rather than read from the row, so
 * bytes a completer promoted before dying are still named by whoever has to reclaim them.
 */
object ImportArchiveKey {
    fun forImport(importId: UUID): String = "imports/$importId.zip"
}
