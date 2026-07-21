package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

open class ReauthenticationHeaderError(message: String, code: ErrorCode) : BaseError(message, code)

class MalformedReauthenticationError :
    ReauthenticationHeaderError(
        message = "Malformed or unsupported re-authentication factor",
        code = ErrorCode.UNSUPPORTED_REAUTHENTICATION_FACTOR,
    )
