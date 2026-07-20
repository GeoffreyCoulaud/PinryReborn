package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

open class BoardCreationError(message: String, code: ErrorCode, cause: Throwable? = null) :
    BaseError(message, code, cause)
