package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID.randomUUID

@ApplicationScoped
class TagCreator(
    private val tagRepository: TagRepositoryInterface,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    /** A tag this instance invents, stamped from the clock as every use case stamps what it invents. */
    fun findOrCreate(
        name: String,
        user: User,
    ): Tag = resolve(name = name, user = user, createdAt = clock.now()).tag

    /**
     * One transaction around the pair, as `EbeanTaskQueue.enqueue` does: the single connection
     * serialises each statement, not a pair. Here, not per caller, so every tag resolver is covered.
     */
    fun resolve(
        name: String,
        user: User,
        // Not the clock: the user data import restores the archive's instant (ADR 0015, decision 3).
        createdAt: Instant,
    ): ResolvedTag =
        transactionRunner.inTransaction {
            tagRepository
                .findUserTagByName(name = name, user = user)
                ?.let { ResolvedTag(tag = it, created = false) }
                ?: ResolvedTag(
                    tag =
                        tagRepository.saveTag(
                            Tag(id = randomUUID(), name = name, author = user, createdAt = createdAt),
                        ),
                    created = true,
                )
        }

    /** Which half ran, since a caller counting its skips cannot tell them apart from the tag alone. */
    data class ResolvedTag(
        val tag: Tag,
        val created: Boolean,
    )
}
