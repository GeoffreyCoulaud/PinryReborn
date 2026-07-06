package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

open class PinRetrievalError(
    message: String,
    code: ErrorCode,
    cause: Throwable? = null
) :
    BaseError(message, code, cause)

class PinRetrievalPinDoesNotExistError : PinRetrievalError(
    "Pin does not exist",
    ErrorCode.PIN_DOES_NOT_EXIST
)

class PinRetrievalPermissionError : PinRetrievalError(
    "Insufficient permissions",
    ErrorCode.PIN_INSUFFICIENT_PERMISSIONS
)
