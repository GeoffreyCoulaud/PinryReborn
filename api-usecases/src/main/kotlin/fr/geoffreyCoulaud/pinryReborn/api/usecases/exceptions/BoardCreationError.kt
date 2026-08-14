package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board

open class BoardCreationError(message: String, code: ErrorCode, cause: Throwable? = null) :
    BaseError(message, code, cause)

/**
 * The author already holds the name, ASCII case folded, in any state (ADR 0009 decision 2: the index
 * decides, so [holder] is read back after the refusal, and is null when a hard delete raced it).
 */
class BoardNameAlreadyExistsError(holder: Board?, cause: Throwable) :
    BoardCreationError(detailFor(holder), ErrorCode.BOARD_NAME_ALREADY_EXISTS, cause) {
    private companion object {
        fun detailFor(holder: Board?): String =
            if (holder?.softDeletedAt != null) {
                "This board name is held by a board in your recycle bin"
            } else {
                "This board name is already taken"
            }
    }
}
