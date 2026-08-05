package fr.geoffreyCoulaud.pinryReborn.api.domain.users

/**
 * Raised by [fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface.saveUser]
 * when the name is already held, enforced by the persistence layer's case-insensitive unique index
 * over every row of `users`. Two rules follow from the index covering every row: a name differing
 * only by case is the same name, and an account pending deletion still holds its own.
 *
 * `saveUser` also updates, so renaming an account onto a taken name raises this too. That is the
 * same rule reached by another route and needs no separate handling.
 *
 * A domain exception, not a use-case [fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BaseError]:
 * `api-persistence-sqlite` must not depend on `api-usecases`. The adapter throws this; `UserCreator`
 * catches it and rethrows `UsernameAlreadyTakenError`, the same layering as
 * `PasswordChangeCollisionException`.
 */
class UsernameAlreadyTakenException(cause: Throwable? = null) :
    Exception("This username is already taken", cause)
