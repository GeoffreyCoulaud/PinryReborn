package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinDeletionPermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinDeletionPinAlreadySoftDeletedError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinDeletionPinDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinDeletionPinNotSoftDeletedError
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class PinRecycleBin(
    private val pinRepository: PinRepositoryInterface,
    private val imageRepository: ImageRepositoryInterface,
    private val imageStore: ImageStore,
    private val clearPinDownload: ClearPinDownload,
    private val renditionCache: RenditionCache,
    private val clock: Clock,
) {
    private fun findPinAndValidateOwnership(pinId: UUID, user: User): Pin {
        val pin = pinRepository.findPinById(id = pinId) ?: throw PinDeletionPinDoesNotExistError()
        if (pin.author != user) throw PinDeletionPermissionError()
        return pin
    }

    fun softDelete(pinId: UUID, user: User): Pin {
        val pin = findPinAndValidateOwnership(pinId, user)
        if (pin.softDeletedAt != null) throw PinDeletionPinAlreadySoftDeletedError()
        return pinRepository.softDeletePin(pin = pin, at = clock.now())
    }

    fun restore(pinId: UUID, user: User): Pin {
        val pin = findPinAndValidateOwnership(pinId, user)
        if (pin.softDeletedAt == null) throw PinDeletionPinNotSoftDeletedError()
        return pinRepository.restorePin(pin = pin, at = clock.now())
    }

    fun permanentlyDelete(pinId: UUID, user: User) {
        val pin = findPinAndValidateOwnership(pinId, user)
        if (pin.softDeletedAt == null) throw PinDeletionPinNotSoftDeletedError()
        clearPinDownload.clear(pin.id)
        val image = imageRepository.findByPinId(pin.id)
        imageRepository.deleteByPinId(pin.id)
        pinRepository.permanentlyDeletePin(pin)
        image?.let {
            imageStore.deleteQuietly(it.storageKey)
            renditionCache.evictImageQuietly(it.id)
        }
    }

    fun emptyRecycleBin(user: User) {
        val pins = pinRepository.findAllSoftDeletedPinsForUser(user)
        val images = pins.mapNotNull { pin ->
            clearPinDownload.clear(pin.id)
            val image = imageRepository.findByPinId(pin.id)
            imageRepository.deleteByPinId(pin.id)
            image
        }
        pinRepository.permanentlyDeleteAllSoftDeletedPinsForUser(user)
        images.forEach {
            imageStore.deleteQuietly(it.storageKey)
            renditionCache.evictImageQuietly(it.id)
        }
    }
}
