package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

open class PasswordChangeError(message: String, code: ErrorCode, cause: Throwable? = null) :
    BaseError(message, code, cause)

class PasswordPreviouslyUsedError :
    PasswordChangeError(message = "Password was previously used", code = ErrorCode.PASSWORD_PREVIOUSLY_USED)

class PasswordChangedTooSoonError(override val retryAfterSeconds: Long) :
    PasswordChangeError(
        message = "Password was changed too recently",
        code = ErrorCode.PASSWORD_CHANGED_TOO_SOON,
    ),
    ThrottledError

class PasswordChangeCollisionError(cause: Throwable) :
    PasswordChangeError(
        message = "A password change is already in progress",
        code = ErrorCode.PASSWORD_CHANGE_COLLISION,
        cause = cause,
    )
