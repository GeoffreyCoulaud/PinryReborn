package fr.geoffreyCoulaud.pinryReborn.api.domain.exports

/**
 * Raised by [fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface.save]
 * when a second `PENDING` export is requested for a user that already has one in flight (enforced by
 * the persistence layer's partial unique index).
 *
 * Deliberately a domain exception, not a use-case `BaseError`: `api-persistence-sqlite` must not
 * depend on `api-usecases`. The adapter throws this; `UserDataExportRequester` (api-usecases) catches
 * it and rethrows `ExportAlreadyInProgressError` for the presentation layer, exactly as
 * `ImageStore.stage` throws the domain `ImageTooLargeException` and `SetPinImage` translates it.
 */
class ExportAlreadyInProgressException(cause: Throwable? = null) :
    Exception("An export is already in progress for this user", cause)
