package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import java.util.UUID

interface ImageRepositoryInterface {
    /**
     * Create or replace the image for a pin, in a single transaction.
     */
    fun save(image: Image): Image

    /**
     * Find the image attached to a pin, if any.
     */
    fun findByPinId(pinId: UUID): Image?

    /**
     * Delete the image attached to a pin, if any.
     */
    fun deleteByPinId(pinId: UUID)
}
