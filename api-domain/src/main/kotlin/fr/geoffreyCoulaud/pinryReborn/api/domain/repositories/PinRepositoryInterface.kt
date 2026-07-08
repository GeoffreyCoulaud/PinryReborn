package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import java.util.UUID

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
     * Soft-delete a pin by setting its softDeletedAt timestamp
     */
    fun softDeletePin(pin: Pin): Pin

    /**
     * Restore a soft-deleted pin by clearing its softDeletedAt timestamp
     */
    fun restorePin(pin: Pin): Pin

    /**
     * Permanently delete a pin and its tag associations
     */
    fun permanentlyDeletePin(pin: Pin)

    /**
     * Permanently delete all soft-deleted pins for a user
     */
    fun permanentlyDeleteAllSoftDeletedPinsForUser(user: User)

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
}
