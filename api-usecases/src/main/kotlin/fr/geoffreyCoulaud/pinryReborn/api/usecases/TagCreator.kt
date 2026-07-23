package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID.randomUUID

@ApplicationScoped
class TagCreator(
    private val tagRepository: TagRepositoryInterface,
    private val clock: Clock,
) {
    fun findOrCreate(
        name: String,
        user: User,
    ): Tag =
        tagRepository.findUserTagByName(name = name, user = user)
            ?: tagRepository.saveTag(
                Tag(id = randomUUID(), name = name, author = user, createdAt = clock.now()),
            )
}
