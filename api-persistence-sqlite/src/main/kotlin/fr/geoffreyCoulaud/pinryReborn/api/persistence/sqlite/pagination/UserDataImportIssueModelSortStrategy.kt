package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserDataImportIssueModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QUserDataImportIssueModel

/**
 * Ordered on `id` alone, which is arbitrary but total and stable: an issue carries no instant, and its
 * `line` is null for the archive-level kinds, so neither can key a page boundary without ties.
 */
class UserDataImportIssueModelSortStrategy :
    ModelSortStrategy<UserDataImportIssueModel, QUserDataImportIssueModel>() {
    override fun filterCursorAndForwardNeighbors(
        cursor: ModelCursor<UserDataImportIssueModel>,
        query: QUserDataImportIssueModel,
    ): QUserDataImportIssueModel = query.raw("id >= ?", cursor.pivot.id)

    override fun filterCursorAndBackwardNeighbors(
        cursor: ModelCursor<UserDataImportIssueModel>,
        query: QUserDataImportIssueModel,
    ): QUserDataImportIssueModel = query.raw("id <= ?", cursor.pivot.id)

    override fun sortCursorAndForwardNeighbors(query: QUserDataImportIssueModel): QUserDataImportIssueModel =
        query.orderBy().id.asc()

    override fun sortCursorAndBackwardNeighbors(query: QUserDataImportIssueModel): QUserDataImportIssueModel =
        query.orderBy().id.desc()
}
