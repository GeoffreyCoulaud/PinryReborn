package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.boards.BoardNameAlreadyTakenException
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import java.time.Instant
import java.util.UUID

// 12 methods trips detekt's default per-interface threshold. Suppressed rather than split,
// mirroring PinRepositoryInterface's precedent for the same rule.
@Suppress("TooManyFunctions")
interface BoardRepositoryInterface {
    /**
     * Create or update a board from the given domain data.
     * @throws BoardNameAlreadyTakenException when the author already holds the name, ASCII case folded.
     */
    fun saveBoard(board: Board): Board

    /**
     * Find the board holding [name] for [user] in **any** state, or null: a recycled board holds its name.
     * The fold is the index's own, ASCII case only, so two names differing outside A to Z are two names.
     */
    fun findBoardForUserByName(user: User, name: String): Board?

    /** Find a board by its ID regardless of state (active or recycled), or null. */
    fun findBoardById(id: UUID): Board?

    /** Find an active (non soft-deleted) board by its ID, or null. */
    fun findActiveBoardById(id: UUID): Board?

    /** Find all active boards for a user, sorted by name (case-insensitive), id as tie-breaker. */
    fun findActiveBoardsForUser(user: User): List<Board>

    /** Find all recycled boards for a user, sorted by name (case-insensitive), id as tie-breaker. */
    fun findRecycledBoardsForUser(user: User): List<Board>

    /**
     * Soft-delete a board, recording [at] as both its softDeletedAt and its updatedAt: recycling is
     * a modification like any other. Keeps its pin memberships.
     */
    fun softDeleteBoard(board: Board, at: Instant): Board

    /**
     * Restore a soft-deleted board by clearing its softDeletedAt, recording [at] as its updatedAt.
     */
    fun restoreBoard(board: Board, at: Instant): Board

    /** Permanently delete a board and its pin memberships. */
    fun permanentlyDeleteBoard(board: Board)

    /** Permanently delete all recycled boards for a user (and their pin memberships). */
    fun permanentlyDeleteAllRecycledBoardsForUser(user: User)

    /** Permanently delete all boards for a user regardless of state (active and recycled). */
    fun permanentlyDeleteAllBoardsForUser(user: User)

    /** Count active (non soft-deleted) pins currently in the board. */
    fun countActivePinsInBoard(boardId: UUID): Int
}
