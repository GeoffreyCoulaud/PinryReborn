package fr.geoffreyCoulaud.pinryReborn.api.domain.boards

/**
 * Raised on save when the author already holds the name, ASCII case folded, in any state.
 * A domain exception, not a `BaseError`: `api-persistence-sqlite` cannot see `api-usecases`.
 */
class BoardNameAlreadyTakenException(cause: Throwable) :
    Exception("This board name is already taken", cause)
