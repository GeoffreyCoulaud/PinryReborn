package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.boards.BoardNameAlreadyTakenException
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BoardNameAlreadyExistsError
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
        return try {
            boardRepository.saveBoard(
                board.copy(name = name, description = description, updatedAt = clock.now()),
            )
        } catch (error: BoardNameAlreadyTakenException) {
            // Same read-after-refusal as BoardCreator: renaming is the second of the sites the index
            // refuses, and the client is told when the holder sits in the recycle bin.
            throw BoardNameAlreadyExistsError(
                holder = boardRepository.findBoardForUserByName(user = user, name = name),
                cause = error,
            )
        }
    }
}
