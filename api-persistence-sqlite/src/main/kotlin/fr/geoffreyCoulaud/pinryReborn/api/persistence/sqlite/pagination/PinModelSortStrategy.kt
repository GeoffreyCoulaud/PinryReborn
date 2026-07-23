package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.PinModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QPinModel
import io.ebean.typequery.PInstant
import java.time.Instant
import java.util.UUID

/**
 * Cursor pagination needs a strict total order. Ordering on a timestamp alone stalls the cursor as
 * soon as a page boundary falls inside a group of rows sharing that timestamp: the next page starts
 * again from the same timestamp and keeps returning the same rows. Every strategy below therefore
 * orders on the pair `(timestamp, id)` and filters with the matching keyset predicate.
 *
 * The pivot row itself is deliberately kept in range (the comparison on `id` is inclusive):
 * [ModelPaginationHelper] strips it from the page once the rows are read.
 */
sealed class PinModelSortStrategy : ModelSortStrategy<PinModel, QPinModel>() {
    /**
     * Keep the pivot and everything ordered after it, walking down `(timestamp, id)`:
     * `timestamp < pivot OR (timestamp = pivot AND id <= pivotId)`.
     */
    protected fun filterDownFrom(
        query: QPinModel,
        column: (QPinModel) -> PInstant<QPinModel>,
        pivotValue: Instant?,
        pivotId: UUID,
    ): QPinModel =
        column(query.or())
            .lessThan(pivotValue)
            .let { column(it.and()).equalTo(pivotValue) }
            .raw("id <= ?", pivotId)
            .endAnd()
            .endOr()

    /**
     * Keep the pivot and everything ordered before it, walking up `(timestamp, id)`:
     * `timestamp > pivot OR (timestamp = pivot AND id >= pivotId)`.
     */
    protected fun filterUpFrom(
        query: QPinModel,
        column: (QPinModel) -> PInstant<QPinModel>,
        pivotValue: Instant?,
        pivotId: UUID,
    ): QPinModel =
        column(query.or())
            .greaterThan(pivotValue)
            .let { column(it.and()).equalTo(pivotValue) }
            .raw("id >= ?", pivotId)
            .endAnd()
            .endOr()

    /**
     * Sort strategy used to get pages of `PinModel`s in ascending creation date.
     */
    class CreatedAtAsc : PinModelSortStrategy() {
        override fun filterCursorAndForwardNeighbors(
            cursor: ModelCursor<PinModel>,
            query: QPinModel,
        ): QPinModel = filterUpFrom(query, { it.createdAt }, cursor.pivot.createdAt, cursor.pivot.id)

        override fun filterCursorAndBackwardNeighbors(
            cursor: ModelCursor<PinModel>,
            query: QPinModel,
        ): QPinModel = filterDownFrom(query, { it.createdAt }, cursor.pivot.createdAt, cursor.pivot.id)

        override fun sortCursorAndForwardNeighbors(query: QPinModel): QPinModel =
            query
                .orderBy()
                .createdAt
                .asc()
                .id
                .asc()

        override fun sortCursorAndBackwardNeighbors(query: QPinModel): QPinModel =
            query
                .orderBy()
                .createdAt
                .desc()
                .id
                .desc()
    }

    /**
     * Sort strategy used to get pages of `PinModel`s in descending creation date.
     */
    class CreatedAtDesc : PinModelSortStrategy() {
        override fun filterCursorAndForwardNeighbors(
            cursor: ModelCursor<PinModel>,
            query: QPinModel,
        ): QPinModel = filterDownFrom(query, { it.createdAt }, cursor.pivot.createdAt, cursor.pivot.id)

        override fun filterCursorAndBackwardNeighbors(
            cursor: ModelCursor<PinModel>,
            query: QPinModel,
        ): QPinModel = filterUpFrom(query, { it.createdAt }, cursor.pivot.createdAt, cursor.pivot.id)

        override fun sortCursorAndForwardNeighbors(query: QPinModel): QPinModel =
            query
                .orderBy()
                .createdAt
                .desc()
                .id
                .desc()

        override fun sortCursorAndBackwardNeighbors(query: QPinModel): QPinModel =
            query
                .orderBy()
                .createdAt
                .asc()
                .id
                .asc()
    }

    /**
     * Sort strategy used to get pages of `PinModel`s in descending soft-deletion date.
     */
    class DeletedAtDesc : PinModelSortStrategy() {
        override fun filterCursorAndForwardNeighbors(
            cursor: ModelCursor<PinModel>,
            query: QPinModel,
        ): QPinModel = filterDownFrom(query, { it.softDeletedAt }, cursor.pivot.softDeletedAt, cursor.pivot.id)

        override fun filterCursorAndBackwardNeighbors(
            cursor: ModelCursor<PinModel>,
            query: QPinModel,
        ): QPinModel = filterUpFrom(query, { it.softDeletedAt }, cursor.pivot.softDeletedAt, cursor.pivot.id)

        override fun sortCursorAndForwardNeighbors(query: QPinModel): QPinModel =
            query
                .orderBy()
                .softDeletedAt
                .desc()
                .id
                .desc()

        override fun sortCursorAndBackwardNeighbors(query: QPinModel): QPinModel =
            query
                .orderBy()
                .softDeletedAt
                .asc()
                .id
                .asc()
    }

    companion object {
        fun fromDomain(strategy: PinSortStrategy) =
            when (strategy) {
                PinSortStrategy.CREATED_AT_ASC -> CreatedAtAsc()
                PinSortStrategy.CREATED_AT_DESC -> CreatedAtDesc()
                PinSortStrategy.DELETED_AT_DESC -> DeletedAtDesc()
            }
    }
}
