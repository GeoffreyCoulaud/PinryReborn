package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.exceptions.UserModelDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.queries.UserQueries
import java.util.UUID

/**
 * The account a new row is about to be hung off, resolved once for every repository that needs one.
 *
 * A tombstoned account keeps its row, so this lookup is what refuses it a session, an export or a
 * credential. Whether it stays `active()` rather than `any()` is one question, asked here instead
 * of once per repository, and it applies to the system's own writes as much as to the account's.
 */
internal object ActiveUserModels {
    /** The active account with this id, or [UserModelDoesNotExistError] when there is none. */
    fun resolve(userId: UUID): UserModel =
        UserQueries.active().id.equalTo(userId).findOne() ?: throw UserModelDoesNotExistError()
}
