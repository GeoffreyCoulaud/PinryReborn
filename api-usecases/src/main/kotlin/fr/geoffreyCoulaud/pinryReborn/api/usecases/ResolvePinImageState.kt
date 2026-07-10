package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageDownloadRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePinDoesNotExistError
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class ResolvePinImageState(
    private val pinRepository: PinRepositoryInterface,
    private val imageRepository: ImageRepositoryInterface,
    private val imageDownloadRepository: ImageDownloadRepositoryInterface,
) {
    fun resolve(pinId: UUID, requester: User): PinImageState {
        val pin = pinRepository.findPinById(pinId) ?: throw ImagePinDoesNotExistError()
        if (pin.author.id != requester.id) throw ImagePermissionError()
        return PinImageState.derive(imageRepository.findByPinId(pinId), imageDownloadRepository.findByPinId(pinId))
    }
}
