package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinBoardSettingInvalidBoardError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinBoardSettingPermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinBoardSettingPinDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinBoardSettingSoftDeletedPinError
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class PinBoardSetter(
    private val pinRepository: PinRepositoryInterface,
    private val boardRepository: BoardRepositoryInterface,
    private val clock: Clock,
) {
    fun setBoards(pinId: UUID, boardIds: List<UUID>, user: User): Pin {
        val pin = pinRepository.findPinById(id = pinId) ?: throw PinBoardSettingPinDoesNotExistError()
        if (pin.author != user) throw PinBoardSettingPermissionError()
        if (pin.softDeletedAt != null) throw PinBoardSettingSoftDeletedPinError()

        val boards = boardIds.map { resolveBoard(it, user) }
        return pinRepository.savePin(pin.copy(boards = boards, updatedAt = clock.now()))
    }

    private fun resolveBoard(boardId: UUID, user: User): Board {
        val board = boardRepository.findActiveBoardById(boardId) ?: throw PinBoardSettingInvalidBoardError()
        if (board.author != user) throw PinBoardSettingInvalidBoardError()
        return board
    }
}
