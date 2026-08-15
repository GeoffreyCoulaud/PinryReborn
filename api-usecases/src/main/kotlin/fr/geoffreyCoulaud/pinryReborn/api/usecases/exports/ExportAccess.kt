package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import java.util.UUID

/**
 * The one write of an export row two actors can reach: read and written in one transaction, and an
 * absent row refuses, `merge` being an upsert. A refused [held] answers null (`docs/adr/0016`).
 */
internal fun UserDataExportRepositoryInterface.saveFenced(
    transactionRunner: TransactionRunner,
    exportId: UUID,
    held: (UserDataExport) -> Boolean,
    update: (UserDataExport) -> UserDataExport,
): UserDataExport? =
    transactionRunner.inTransaction {
        findById(exportId)?.takeIf(held)?.let { save(update(it)) }
    }

/**
 * The same write, answering the row it replaced rather than the one it wrote: a caller whose release
 * depends on the state reads it here, the state it saw before the fence being possibly one old.
 */
internal fun UserDataExportRepositoryInterface.saveFencedOver(
    transactionRunner: TransactionRunner,
    exportId: UUID,
    held: (UserDataExport) -> Boolean,
    update: (UserDataExport) -> UserDataExport,
): UserDataExport? =
    transactionRunner.inTransaction {
        findById(exportId)?.takeIf(held)?.also { save(update(it)) }
    }
