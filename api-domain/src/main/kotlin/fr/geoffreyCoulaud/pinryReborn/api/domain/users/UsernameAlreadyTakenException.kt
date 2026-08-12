package fr.geoffreyCoulaud.pinryReborn.api.domain.users

/**
 * Raised by [fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface.saveUser]
 * when the name is already held, enforced by the persistence layer's case-insensitive unique index
 * over every row of `users`: a name differing only by case is the same name, and an account pending
 * deletion still holds its own.
 *
 * A domain exception, not a use-case [fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BaseError]:
 * `api-persistence-sqlite` must not depend on `api-usecases`. The adapter throws this; `UserCreator`
 * catches it and rethrows `UsernameAlreadyTakenError`, the same layering as
 * `PasswordChangeCollisionException`.
 */
class UsernameAlreadyTakenException(cause: Throwable) :
    Exception("This username is already taken", cause)
