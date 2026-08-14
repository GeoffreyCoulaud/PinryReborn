package fr.geoffreyCoulaud.pinryReborn.api.domain.imports

/**
 * Raised on save when the user already holds an active import, enforced by the partial unique index.
 * A domain exception, not a `BaseError`: `api-persistence-sqlite` cannot see `api-usecases`.
 */
class ImportAlreadyInProgressException(cause: Throwable) :
    Exception("An import is already in progress for this user", cause)
