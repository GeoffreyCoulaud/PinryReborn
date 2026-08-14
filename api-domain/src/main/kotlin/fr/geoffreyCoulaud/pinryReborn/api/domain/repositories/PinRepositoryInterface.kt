package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import java.time.Instant
import java.util.UUID

// 12 methods trips detekt's default per-interface threshold. Suppressed rather than split,
// mirroring TaskQueueInterface's precedent for the same rule: it's one cohesive repository
// surface, and splitting it would fragment it across artificial interfaces for no readability
// gain.
@Suppress("TooManyFunctions")
interface PinRepositoryInterface {
    /**
     * Create or update a pin from the given domain data.
     */
    fun savePin(pin: Pin): Pin

    /**
     * Find a pin by its ID
     */
    fun findPinById(id: UUID): Pin?

    /**
     * Find pins with pagination support
     * @param cursor The cursor to find pins relative to
     * @param pageSize Number of pins to return (will be capped at server max)
     * @param sortStrategy The sort strategy
     * @return A page of pins with pagination information
     */
    fun findPinsForUser(
        reader: User,
        cursor: Cursor?,
        pageSize: Int,
        sortStrategy: PinSortStrategy,
    ): Page<Pin>

    /**
     * Find all active pins for a user (excludes soft-deleted)
     */
    fun findAllPinsForUser(user: User): List<Pin>

    /**
     * All pin ids for the author (active and soft-deleted), without mapping the author - safe
     * when the author is itself soft-deleted (deletion cleaner).
     */
    fun findAllPinIdsForUser(user: User): List<UUID>

    /**
     * Soft-delete a pin, recording [at] as both its softDeletedAt and its updatedAt: recycling is
     * a modification like any other.
     */
    fun softDeletePin(pin: Pin, at: Instant): Pin

    /**
     * Restore a soft-deleted pin by clearing its softDeletedAt, recording [at] as its updatedAt.
     */
    fun restorePin(pin: Pin, at: Instant): Pin

    /**
     * Permanently delete a pin and its tag associations
     */
    fun permanentlyDeletePin(pin: Pin)

    /**
     * Permanently delete all soft-deleted pins for a user
     */
    fun permanentlyDeleteAllSoftDeletedPinsForUser(user: User)

    /**
     * Permanently delete all pins for a user regardless of state (active and soft-deleted).
     */
    fun permanentlyDeleteAllPinsForUser(user: User)

    /**
     * Find soft-deleted pins for a user with pagination support
     */
    fun findSoftDeletedPinsForUser(
        reader: User,
        cursor: Cursor?,
        pageSize: Int,
        sortStrategy: PinSortStrategy,
    ): Page<Pin>

    /**
     * Find all soft-deleted pins for a user (unpaginated)
     */
    fun findAllSoftDeletedPinsForUser(user: User): List<Pin>

    /**
     * Find active pins belonging to a board, with pagination support.
     * Excludes soft-deleted pins. The board's own existence/ownership is checked by the caller.
     */
    fun findActivePinsForBoard(
        reader: User,
        boardId: UUID,
        cursor: Cursor?,
        pageSize: Int,
        sortStrategy: PinSortStrategy,
    ): Page<Pin>

    /**
     * Find every board a pin belongs to, regardless of the board's own state (active or
     * recycled). Unlike the boards exposed on a mapped [Pin], this does NOT filter out recycled
     * boards: `softDeleteBoard` keeps the join row, and the export must see it, exactly as
     * recycled pins are already exported with their own deletion marker.
     */
    fun findBoardsForPinIncludingRecycled(pinId: UUID): List<Board>

    /**
     * Ids of [user]'s pins whose image carries [contentHash], in **every** state, so an import never
     * re-creates a recycled pin. Author-scoped: a content hash is otherwise an oracle on other accounts.
     */
    fun findPinIdsByContentHashForUser(user: User, contentHash: String): List<UUID>
}
