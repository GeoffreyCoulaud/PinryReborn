package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImageDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePinDoesNotExistError
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class DeletePinImage(
    private val pinRepository: PinRepositoryInterface,
    private val imageRepository: ImageRepositoryInterface,
    private val imageStore: ImageStore,
    private val clearPinDownload: ClearPinDownload,
    private val renditionCache: RenditionCache,
) {
    fun delete(pinId: UUID, requester: User) {
        val pin = pinRepository.findPinById(pinId) ?: throw ImagePinDoesNotExistError()
        if (pin.author.id != requester.id) throw ImagePermissionError()
        val image = imageRepository.findByPinId(pinId)
        if (image != null) {
            imageRepository.deleteByPinId(pinId)
            imageStore.deleteQuietly(image.storageKey)
            renditionCache.evictImageQuietly(image.id)
            clearPinDownload.clear(pinId)
            return
        }
        // No image row: a DELETE during a fetch must still cancel the in-flight/failed download and
        // leave nothing pending (spec section 7). Only when there is nothing at all to remove is this a 404.
        if (!clearPinDownload.clear(pinId)) throw ImageDoesNotExistError()
    }
}
