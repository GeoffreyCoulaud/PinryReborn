package fr.geoffreyCoulaud.pinryReborn.api.domain.security

/**
 * Raised by
 * [fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface.saveUserPasswordHash]
 * when a second password hash for one user would share another's creation instant, enforced by the
 * persistence layer's `(user_id, when_created)` unique index (`1.18.sql:2`).
 *
 * A domain exception, not a use-case [fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BaseError]:
 * `api-persistence-sqlite` must not depend on `api-usecases`. The adapter throws this;
 * `PasswordChanger` catches it and rethrows `PasswordChangeCollisionError`, the same layering as
 * `ExportAlreadyInProgressException`.
 */
class PasswordChangeCollisionException(cause: Throwable) :
    Exception("A password hash for this user already exists at that instant", cause)
