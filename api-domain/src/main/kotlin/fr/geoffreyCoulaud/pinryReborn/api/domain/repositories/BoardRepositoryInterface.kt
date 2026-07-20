package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import java.util.UUID

interface BoardRepositoryInterface {
    /** Create or update a board from the given domain data. */
    fun saveBoard(board: Board): Board

    /** Find an active (non soft-deleted) board by its ID, or null. */
    fun findActiveBoardById(id: UUID): Board?

    /** Find a recycled (soft-deleted) board by its ID, or null. */
    fun findRecycledBoardById(id: UUID): Board?

    /** Find all active boards for a user, sorted by name (case-insensitive), id as tie-breaker. */
    fun findActiveBoardsForUser(user: User): List<Board>

    /** Find all recycled boards for a user, sorted by name (case-insensitive), id as tie-breaker. */
    fun findRecycledBoardsForUser(user: User): List<Board>

    /** Soft-delete a board by setting its softDeletedAt timestamp. Keeps pin_board rows. */
    fun softDeleteBoard(board: Board): Board

    /** Restore a soft-deleted board by clearing its softDeletedAt timestamp. */
    fun restoreBoard(board: Board): Board

    /** Permanently delete a board and its pin_board associations. */
    fun permanentlyDeleteBoard(board: Board)

    /** Permanently delete all recycled boards for a user (and their pin_board associations). */
    fun permanentlyDeleteAllRecycledBoardsForUser(user: User)

    /** Count active (non soft-deleted) pins currently in the board. */
    fun countActivePinsInBoard(boardId: UUID): Int
}
