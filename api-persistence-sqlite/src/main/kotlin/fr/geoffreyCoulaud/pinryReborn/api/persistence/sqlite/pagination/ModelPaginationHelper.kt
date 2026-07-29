package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.CursorDirection.BACKWARD
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.CursorDirection.FORWARD
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.BaseModel
import io.ebean.typequery.QueryBean

/**
 * Cursor pagination over any model, stateless, its two types read off the call site's arguments so
 * no caller has to name a query bean to reach it.
 */
object ModelPaginationHelper {
    fun <M : BaseModel, Q : QueryBean<M, Q>> getPage(
        cursor: ModelCursor<M>?,
        pageSize: Int,
        baseQuery: Q,
        sortStrategy: ModelSortStrategy<M, Q>,
    ): ModelPage<M> {
        val direction = if (cursor != null) cursor.direction else FORWARD

        // +1 to include the cursor (if there is one)
        // +2 to know if there are more than the page in the direction
        val maxRows = pageSize + if (cursor == null) 1 else 2

        var hasMoreInDirection: Boolean = false
        val elements =
            baseQuery
                .let { sortStrategy.filterCursorAndNeighbors(query = it, cursor = cursor) }
                .let { sortStrategy.sortCursorNeighbors(query = it, cursor = cursor) }
                .setMaxRows(maxRows)
                .findList()
                .let {
                    // Determine if there are more pages
                    // When cursor is present, the list includes the pivot, so we need +1
                    val threshold = if (cursor != null) pageSize + 1 else pageSize
                    hasMoreInDirection = it.size > threshold
                    if (hasMoreInDirection) it.dropLast(1) else it
                }
                // Reorder the elements if backward to preserve order
                .let { if (direction == BACKWARD) it.reversed() else it }
                // Remove the pivot
                .filterNot { cursor != null && it.id == cursor.pivot.id }

        val previousCursor =
            when (direction) {
                FORWARD -> (elements.firstOrNull())
                BACKWARD -> elements.lastOrNull()?.takeIf { hasMoreInDirection }
            }?.let { ModelCursor(pivot = it, direction = BACKWARD) }

        val nextCursor =
            when (direction) {
                FORWARD -> elements.lastOrNull()?.takeIf { hasMoreInDirection }
                BACKWARD -> elements.firstOrNull()
            }?.let { ModelCursor(pivot = it, direction = FORWARD) }

        return ModelPage(
            items = elements,
            previousCursor = previousCursor,
            nextCursor = nextCursor,
        )
    }
}
