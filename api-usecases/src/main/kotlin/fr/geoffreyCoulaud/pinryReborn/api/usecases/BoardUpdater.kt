package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class BoardUpdater(
    private val boardRepository: BoardRepositoryInterface,
    private val boardGetter: BoardGetter,
    private val clock: Clock,
) {
    fun update(boardId: UUID, name: String, description: String, user: User): Board {
        val board = boardGetter.getActiveBoardForUser(boardId = boardId, reader = user)
        return boardRepository.saveBoard(
            board.copy(name = name, description = description, updatedAt = clock.now()),
        )
    }
}
