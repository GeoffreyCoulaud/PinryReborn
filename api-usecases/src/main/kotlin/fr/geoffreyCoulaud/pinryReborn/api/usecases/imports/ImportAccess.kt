package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportNotAwaitingArchiveError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportPermissionError
import java.util.UUID

/** The one existence-then-ownership check, so no use case can answer either question differently. */
internal fun UserDataImportRepositoryInterface.findOwned(
    user: User,
    importId: UUID,
): UserDataImport {
    val userDataImport = findById(importId) ?: throw ImportDoesNotExistError()
    if (userDataImport.userId != user.id) throw ImportPermissionError()
    return userDataImport
}

/** Owner before state, so a stranger learns nothing from the refusal about an import that is not his. */
internal fun UserDataImportRepositoryInterface.findAwaitingArchive(
    user: User,
    importId: UUID,
): UserDataImport =
    findOwned(user, importId).also {
        if (it.state != UserDataImportState.AWAITING_ARCHIVE) throw ImportNotAwaitingArchiveError()
    }

/**
 * The one write of an import row two actors can reach: read and written in one transaction, since a
 * save of a copy read earlier restores every column that copy carried. A refused [held] answers null.
 */
internal fun UserDataImportRepositoryInterface.saveFenced(
    transactionRunner: TransactionRunner,
    importId: UUID,
    held: (UserDataImport) -> Boolean,
    update: (UserDataImport) -> UserDataImport,
): UserDataImport? =
    transactionRunner.inTransaction {
        findById(importId)?.takeIf(held)?.let { save(update(it)) }
    }
