package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID.randomUUID

@ApplicationScoped
class PinCreator(
    private val tagCreator: TagCreator,
    private val pinRepository: PinRepositoryInterface,
) {
    fun createPin(
        author: User,
        sourceContextUrl: String,
        sourceMediaUrl: String?,
        description: String,
        tags: List<String>,
    ): Pin {

        val tags = tags.map { tagCreator.findOrCreate(name = it, user = author) }
        val pin = Pin(
            id = randomUUID(),
            author = author,
            sourceContextUrl = sourceContextUrl,
            sourceMediaUrl = sourceMediaUrl,
            description = description,
            tags = tags,
            boards = emptyList(),
        )
        return pinRepository.savePin(pin)
    }
}
