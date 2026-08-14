package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.boards.BoardNameAlreadyTakenException
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BoardNameAlreadyExistsError
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID.randomUUID

@ApplicationScoped
class BoardCreator(
    private val boardRepository: BoardRepositoryInterface,
    private val clock: Clock,
) {
    fun create(author: User, name: String, description: String): Board {
        val now = clock.now()
        return try {
            boardRepository.saveBoard(
                Board(
                    id = randomUUID(),
                    author = author,
                    name = name,
                    description = description,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        } catch (error: BoardNameAlreadyTakenException) {
            // Read after the refusal, never before it: the index answers uniqueness, this only
            // decides whether the client is told the recycle bin is holding the name.
            throw BoardNameAlreadyExistsError(
                holder = boardRepository.findBoardForUserByName(user = author, name = name),
                cause = error,
            )
        }
    }
}
