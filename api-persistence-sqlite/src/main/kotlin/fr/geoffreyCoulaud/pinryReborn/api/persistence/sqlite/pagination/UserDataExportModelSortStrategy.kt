package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserDataExportModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QUserDataExportModel

/**
 * A user's export listing has exactly one order: most recently requested first. Ordering on
 * `requestedAt` alone stalls the cursor as soon as a page boundary falls inside a group of rows
 * sharing that timestamp (mirrors the bug fixed for pins in `PinModelSortStrategy`), so this orders
 * on the pair `(requestedAt, id)` and filters with the matching keyset predicate.
 *
 * The pivot row itself is deliberately kept in range (the comparison on `id` is inclusive):
 * [ModelPaginationHelper] strips it from the page once the rows are read.
 */
class UserDataExportModelSortStrategy : ModelSortStrategy<UserDataExportModel, QUserDataExportModel>() {
    override fun filterCursorAndForwardNeighbors(
        cursor: ModelCursor<UserDataExportModel>,
        query: QUserDataExportModel,
    ): QUserDataExportModel =
        query
            .or()
            .requestedAt.lessThan(cursor.pivot.requestedAt)
            .let {
                it
                    .and()
                    .requestedAt.equalTo(cursor.pivot.requestedAt)
                    .raw("id <= ?", cursor.pivot.id)
                    .endAnd()
            }.endOr()

    override fun filterCursorAndBackwardNeighbors(
        cursor: ModelCursor<UserDataExportModel>,
        query: QUserDataExportModel,
    ): QUserDataExportModel =
        query
            .or()
            .requestedAt.greaterThan(cursor.pivot.requestedAt)
            .let {
                it
                    .and()
                    .requestedAt.equalTo(cursor.pivot.requestedAt)
                    .raw("id >= ?", cursor.pivot.id)
                    .endAnd()
            }.endOr()

    override fun sortCursorAndForwardNeighbors(query: QUserDataExportModel): QUserDataExportModel =
        query.orderBy().requestedAt.desc().id.desc()

    override fun sortCursorAndBackwardNeighbors(query: QUserDataExportModel): QUserDataExportModel =
        query.orderBy().requestedAt.asc().id.asc()
}
