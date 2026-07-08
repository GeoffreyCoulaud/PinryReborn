package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImageDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePinDoesNotExistError
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class GetPinImage(
    private val pinRepository: PinRepositoryInterface,
    private val imageRepository: ImageRepositoryInterface,
) {
    fun get(pinId: UUID, requester: User): Image {
        val pin = pinRepository.findPinById(pinId) ?: throw ImagePinDoesNotExistError()
        if (pin.author.id != requester.id) throw ImagePermissionError()
        return imageRepository.findByPinId(pinId) ?: throw ImageDoesNotExistError()
    }
}
