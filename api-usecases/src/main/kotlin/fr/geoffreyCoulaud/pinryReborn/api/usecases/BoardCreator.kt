package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID.randomUUID

@ApplicationScoped
class BoardCreator(
    private val boardRepository: BoardRepositoryInterface,
    private val clock: Clock,
) {
    fun create(author: User, name: String, description: String): Board {
        val now = clock.now()
        return boardRepository.saveBoard(
            Board(
                id = randomUUID(),
                author = author,
                name = name,
                description = description,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}
