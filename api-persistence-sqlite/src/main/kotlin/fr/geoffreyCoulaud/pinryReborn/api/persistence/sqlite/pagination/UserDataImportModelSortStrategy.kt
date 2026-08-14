package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserDataImportModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QUserDataImportModel

/**
 * Most recently requested first, on the pair `(requestedAt, id)`: the instant alone stalls the cursor
 * inside a group sharing it, as it did for pins and for exports. The pivot stays deliberately in range.
 */
class UserDataImportModelSortStrategy : ModelSortStrategy<UserDataImportModel, QUserDataImportModel>() {
    override fun filterCursorAndForwardNeighbors(
        cursor: ModelCursor<UserDataImportModel>,
        query: QUserDataImportModel,
    ): QUserDataImportModel =
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
        cursor: ModelCursor<UserDataImportModel>,
        query: QUserDataImportModel,
    ): QUserDataImportModel =
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

    override fun sortCursorAndForwardNeighbors(query: QUserDataImportModel): QUserDataImportModel =
        query.orderBy().requestedAt.desc().id.desc()

    override fun sortCursorAndBackwardNeighbors(query: QUserDataImportModel): QUserDataImportModel =
        query.orderBy().requestedAt.asc().id.asc()
}
