package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BoardDeletionBoardAlreadySoftDeletedError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BoardDeletionBoardDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BoardDeletionBoardNotSoftDeletedError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BoardDeletionPermissionError
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class BoardRecycleBin(
    private val boardRepository: BoardRepositoryInterface,
    private val clock: Clock,
) {
    // Find the board regardless of state, then validate ownership BEFORE checking state, so a
    // missing board is 404, a non-owner is 403, and a wrong-state board is 409 (mirrors
    // PinRecycleBin.findPinAndValidateOwnership).
    private fun findBoardAndValidateOwnership(boardId: UUID, user: User): Board {
        val board = boardRepository.findBoardById(boardId) ?: throw BoardDeletionBoardDoesNotExistError()
        if (board.author != user) throw BoardDeletionPermissionError()
        return board
    }

    fun softDelete(boardId: UUID, user: User): Board {
        val board = findBoardAndValidateOwnership(boardId, user)
        if (board.softDeletedAt != null) throw BoardDeletionBoardAlreadySoftDeletedError()
        return boardRepository.softDeleteBoard(board = board, at = clock.now())
    }

    fun restore(boardId: UUID, user: User): Board {
        val board = findBoardAndValidateOwnership(boardId, user)
        if (board.softDeletedAt == null) throw BoardDeletionBoardNotSoftDeletedError()
        return boardRepository.restoreBoard(board = board, at = clock.now())
    }

    fun permanentlyDelete(boardId: UUID, user: User) {
        val board = findBoardAndValidateOwnership(boardId, user)
        if (board.softDeletedAt == null) throw BoardDeletionBoardNotSoftDeletedError()
        boardRepository.permanentlyDeleteBoard(board)
    }

    fun emptyRecycleBin(user: User) =
        boardRepository.permanentlyDeleteAllRecycledBoardsForUser(user)

    fun listRecycledBoardsForUser(user: User): List<Board> =
        boardRepository.findRecycledBoardsForUser(user)
}
