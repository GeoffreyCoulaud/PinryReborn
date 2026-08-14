package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImportIssue
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportIssueRepositoryInterface
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/** The import's report, paginated, behind the owner check the import itself is read through. */
@ApplicationScoped
class UserDataImportIssueLister(
    private val getter: UserDataImportGetter,
    private val issueRepository: UserDataImportIssueRepositoryInterface,
) {
    fun list(
        user: User,
        importId: UUID,
        cursor: Cursor?,
        pageSize: Int,
    ): Page<UserDataImportIssue> {
        getter.get(user, importId)
        return issueRepository.findAllForImport(importId, cursor, pageSize)
    }
}
