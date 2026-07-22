package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ExportDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ExportPermissionError
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Reads user data exports: a single owner-checked lookup by id, and the owner's paginated history
 * (spec `docs/specs/2026-07-22-user-data-export.md` §6). [get] is the single place
 * [UserDataExportDownloader] and [UserDataExportDeleter] delegate their existence/ownership check
 * to, so that logic never gets duplicated across use cases.
 */
@ApplicationScoped
class UserDataExportGetter(
    private val repository: UserDataExportRepositoryInterface,
) {
    fun get(user: User, exportId: UUID): UserDataExport {
        val export = repository.findById(exportId) ?: throw ExportDoesNotExistError()
        if (export.userId != user.id) throw ExportPermissionError()
        return export
    }

    fun list(user: User, cursor: Cursor?, pageSize: Int): Page<UserDataExport> =
        repository.findAllForUser(user.id, cursor, pageSize)
}
