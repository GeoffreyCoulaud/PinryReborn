package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

open class BoardRetrievalError(message: String, code: ErrorCode, cause: Throwable? = null) :
    BaseError(message, code, cause)

class BoardRetrievalBoardDoesNotExistError : BoardRetrievalError(
    "Board does not exist", ErrorCode.BOARD_DOES_NOT_EXIST
)

class BoardRetrievalPermissionError : BoardRetrievalError(
    "Insufficient permissions", ErrorCode.BOARD_INSUFFICIENT_PERMISSIONS
)
