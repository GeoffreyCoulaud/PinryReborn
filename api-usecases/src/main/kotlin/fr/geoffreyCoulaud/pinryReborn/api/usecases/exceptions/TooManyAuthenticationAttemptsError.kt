package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

/**
 * Deliberately not a [UserAuthenticationError]: `SessionController` catches that type and rewrites
 * it as a 401, which would swallow this 429.
 */
class TooManyAuthenticationAttemptsError(override val retryAfterSeconds: Long) :
    BaseError(
        message = "Too many authentication attempts",
        code = ErrorCode.TOO_MANY_AUTHENTICATION_ATTEMPTS,
    ),
    ThrottledError
