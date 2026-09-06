package fr.geoffreyCoulaud.pinryReborn.api.usecases

import java.util.UUID

/**
 * The rows of a sweep selection, one page by `id` at a time until a page comes back empty. A row a
 * page acts on leaves the selection; one it fails on is behind the cursor, so every sweep converges.
 */
object SweepPages {
    fun <T> of(idOf: (T) -> UUID, page: (afterId: UUID?) -> List<T>): Sequence<T> =
        sequence {
            var afterId: UUID? = null
            while (true) {
                val rows = page(afterId)
                if (rows.isEmpty()) break
                yieldAll(rows)
                afterId = idOf(rows.last())
            }
        }
}
