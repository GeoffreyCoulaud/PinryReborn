package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

open class BoardDeletionError(message: String, code: ErrorCode, cause: Throwable? = null) :
    BaseError(message, code, cause)

class BoardDeletionBoardDoesNotExistError : BoardDeletionError(
    "Board does not exist", ErrorCode.BOARD_DOES_NOT_EXIST
)

class BoardDeletionPermissionError : BoardDeletionError(
    "Insufficient permissions", ErrorCode.BOARD_INSUFFICIENT_PERMISSIONS
)

class BoardDeletionBoardNotSoftDeletedError : BoardDeletionError(
    "Board is not soft-deleted", ErrorCode.BOARD_NOT_SOFT_DELETED
)

class BoardDeletionBoardAlreadySoftDeletedError : BoardDeletionError(
    "Board is already soft-deleted", ErrorCode.BOARD_ALREADY_SOFT_DELETED
)
