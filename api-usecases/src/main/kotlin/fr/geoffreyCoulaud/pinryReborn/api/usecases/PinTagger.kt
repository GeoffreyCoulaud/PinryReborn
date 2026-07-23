package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinTaggingPermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinTaggingPinDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinTaggingSoftDeletedPinError
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class PinTagger(
    private val tagCreator: TagCreator,
    private val pinRepository: PinRepositoryInterface,
    private val clock: Clock,
) {
    fun setTags(
        pinId: UUID,
        tagNames: List<String>,
        user: User,
    ): Pin {
        val pin = pinRepository.findPinById(id = pinId) ?: throw PinTaggingPinDoesNotExistError()
        if (pin.author != user) throw PinTaggingPermissionError()
        if (pin.softDeletedAt != null) throw PinTaggingSoftDeletedPinError()

        val tags = tagNames.map { tagCreator.findOrCreate(name = it, user = user) }
        val updatedPin = pin.copy(tags = tags, updatedAt = clock.now())
        return pinRepository.savePin(updatedPin)
    }
}
