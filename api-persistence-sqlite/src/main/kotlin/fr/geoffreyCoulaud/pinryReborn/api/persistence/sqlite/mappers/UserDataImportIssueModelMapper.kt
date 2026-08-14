package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImportIssue
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportIssueKind
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserDataImportIssueModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserDataImportModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.ModelCursor

object UserDataImportIssueModelMapper {
    // `userDataImport` arrives as a reference the repository built: an issue row never needs the
    // import loaded, and the walk writes one per anomaly.
    fun UserDataImportIssue.toModel(userDataImport: UserDataImportModel): UserDataImportIssueModel =
        UserDataImportIssueModel(
            id = id,
            userDataImport = userDataImport,
            kind = kind.name,
            line = line,
            subject = subject,
            detail = detail,
        )

    fun UserDataImportIssueModel.toDomain(): UserDataImportIssue =
        UserDataImportIssue(
            id = id,
            importId = userDataImport.id,
            kind = UserDataImportIssueKind.valueOf(kind),
            line = line,
            subject = subject,
            detail = detail,
        )

    fun ModelCursor<UserDataImportIssueModel>.toDomain(): Cursor =
        Cursor(pivotId = this.pivot.id, direction = this.direction)
}
