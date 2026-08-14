package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportAlreadyInProgressException
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportAlreadyInProgressError
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Opens an import and waits for its archive (spec `docs/specs/2026-08-14-user-data-import.md` §6).
 * No task yet: the walk is enqueued once the upload is complete.
 */
@ApplicationScoped
class UserDataImportCreator(
    private val repository: UserDataImportRepositoryInterface,
    private val clock: Clock,
) {
    /**
     * Inserts and lets the partial unique index refuse a second active import. No read answers that
     * question first (ADR 0009 decision 2): unlike the export, this has no second refusal to order.
     */
    fun create(user: User): UserDataImport =
        try {
            repository.save(
                UserDataImport(
                    id = UUID.randomUUID(),
                    userId = user.id,
                    state = UserDataImportState.AWAITING_ARCHIVE,
                    requestedAt = clock.now(),
                ),
            )
        } catch (error: ImportAlreadyInProgressException) {
            throw ImportAlreadyInProgressError(error)
        }
}
