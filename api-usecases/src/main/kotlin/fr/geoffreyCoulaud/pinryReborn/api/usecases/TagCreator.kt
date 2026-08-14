package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID.randomUUID

@ApplicationScoped
class TagCreator(
    private val tagRepository: TagRepositoryInterface,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * One transaction around the pair, as `EbeanTaskQueue.enqueue` does: the single connection
     * serialises each statement, not a pair. Here, not per caller, so every tag resolver is covered.
     */
    fun findOrCreate(
        name: String,
        user: User,
    ): Tag =
        transactionRunner.inTransaction {
            tagRepository.findUserTagByName(name = name, user = user)
                ?: tagRepository.saveTag(
                    Tag(id = randomUUID(), name = name, author = user, createdAt = clock.now()),
                )
        }
}
