package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID.randomUUID

@ApplicationScoped
class BoardCreator(
    private val boardRepository: BoardRepositoryInterface,
) {
    fun create(author: User, name: String, description: String): Board =
        boardRepository.saveBoard(
            Board(id = randomUUID(), author = author, name = name, description = description),
        )
}
