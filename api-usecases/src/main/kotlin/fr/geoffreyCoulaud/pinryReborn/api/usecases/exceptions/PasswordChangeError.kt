package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

open class PasswordChangeError(message: String, code: ErrorCode) : BaseError(message, code)

class PasswordPreviouslyUsedError :
    PasswordChangeError(message = "Password was previously used", code = ErrorCode.PASSWORD_PREVIOUSLY_USED)
