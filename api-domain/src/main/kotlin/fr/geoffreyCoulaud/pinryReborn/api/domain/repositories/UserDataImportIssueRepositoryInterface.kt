package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImportIssue
import java.util.UUID

interface UserDataImportIssueRepositoryInterface {
    fun save(issue: UserDataImportIssue): UserDataImportIssue

    fun findAllForImport(
        importId: UUID,
        cursor: Cursor?,
        pageSize: Int,
    ): Page<UserDataImportIssue>

    /** How many rows are stored, which the report cap bounds; the row's own issueCount is the total. */
    fun countForImport(importId: UUID): Int

    fun deleteAllForImport(importId: UUID)

    fun deleteAllForUser(userId: UUID)
}
