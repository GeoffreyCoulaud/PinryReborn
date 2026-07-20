package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

open class PinBoardSettingError(message: String, code: ErrorCode, cause: Throwable? = null) :
    BaseError(message, code, cause)

class PinBoardSettingPinDoesNotExistError : PinBoardSettingError(
    "Pin does not exist", ErrorCode.PIN_DOES_NOT_EXIST
)

class PinBoardSettingPermissionError : PinBoardSettingError(
    "Insufficient permissions", ErrorCode.PIN_INSUFFICIENT_PERMISSIONS
)

class PinBoardSettingSoftDeletedPinError : PinBoardSettingError(
    "Cannot set boards on a soft-deleted pin", ErrorCode.PIN_ALREADY_SOFT_DELETED
)

class PinBoardSettingInvalidBoardError : PinBoardSettingError(
    "One or more boards do not exist or are not owned by the user",
    ErrorCode.BOARD_INVALID_MEMBERSHIP
)
