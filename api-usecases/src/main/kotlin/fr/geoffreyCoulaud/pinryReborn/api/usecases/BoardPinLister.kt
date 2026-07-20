package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class BoardPinLister(
    private val boardGetter: BoardGetter,
    private val pinRepository: PinRepositoryInterface,
) {
    fun listActivePinsForBoard(
        reader: User,
        boardId: UUID,
        cursor: Cursor?,
        pageSize: Int,
        sort: PinSortStrategy,
    ): Page<Pin> {
        boardGetter.getActiveBoardForUser(boardId = boardId, reader = reader)
        return pinRepository.findActivePinsForBoard(
            reader = reader,
            boardId = boardId,
            cursor = cursor,
            pageSize = pageSize.coerceIn(1, PinGetter.MAX_PAGE_SIZE),
            sortStrategy = sort,
        )
    }
}
