package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

open class UserCreationError(
    message: String,
    code: ErrorCode,
    cause: Throwable? = null
) :
    BaseError(message, code, cause)

class UsernameAlreadyTakenError(cause: Throwable) :
    UserCreationError(
        message = "Username already taken",
        code = ErrorCode.USERNAME_ALREADY_EXISTS,
        cause = cause,
    )
