package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BoardDeletionBoardDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BoardDeletionBoardNotSoftDeletedError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BoardDeletionPermissionError
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class BoardRecycleBin(
    private val boardRepository: BoardRepositoryInterface,
) {
    private fun findActiveOwned(boardId: UUID, user: User): Board {
        val board = boardRepository.findActiveBoardById(boardId) ?: throw BoardDeletionBoardDoesNotExistError()
        if (board.author != user) throw BoardDeletionPermissionError()
        return board
    }

    private fun findRecycledOwned(boardId: UUID, user: User): Board {
        val board = boardRepository.findRecycledBoardById(boardId) ?: throw BoardDeletionBoardNotSoftDeletedError()
        if (board.author != user) throw BoardDeletionPermissionError()
        return board
    }

    fun softDelete(boardId: UUID, user: User): Board =
        boardRepository.softDeleteBoard(findActiveOwned(boardId, user))

    fun restore(boardId: UUID, user: User): Board =
        boardRepository.restoreBoard(findRecycledOwned(boardId, user))

    fun permanentlyDelete(boardId: UUID, user: User) =
        boardRepository.permanentlyDeleteBoard(findRecycledOwned(boardId, user))

    fun emptyRecycleBin(user: User) =
        boardRepository.permanentlyDeleteAllRecycledBoardsForUser(user)

    fun listRecycledBoardsForUser(user: User): List<Board> =
        boardRepository.findRecycledBoardsForUser(user)
}
