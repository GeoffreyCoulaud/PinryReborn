package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BoardRetrievalBoardDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID.randomUUID

class BoardPinListerTest {
    private val boardGetter: BoardGetter = mockk()
    private val pinRepository: PinRepositoryInterface = mockk()
    private val useCase = BoardPinLister(boardGetter = boardGetter, pinRepository = pinRepository)

    private val reader = User(id = randomUUID(), name = createRandomString(), createdAt = TestTime.now)
    private val boardId = randomUUID()
    private val board = Board(
        id = boardId,
        author = reader,
        name = createRandomString(),
        description = createRandomString(),
        createdAt = TestTime.now,
        updatedAt = TestTime.now,
    )
    private val sort = PinSortStrategy.CREATED_AT_ASC

    @Test
    fun `Given an owned board, Then listActivePinsForBoard validates the board then delegates to the repository`() {
        // Given
        val page = Page<Pin>(items = emptyList(), previousCursor = null, nextCursor = null)
        every { boardGetter.getActiveBoardForUser(boardId, reader) } returns board
        every {
            pinRepository.findActivePinsForBoard(
                reader = reader,
                boardId = boardId,
                cursor = null,
                pageSize = 20,
                sortStrategy = sort,
            )
        } returns page

        // When
        val result = useCase.listActivePinsForBoard(reader, boardId, null, 20, sort)

        // Then
        assertSame(page, result)
        verify { boardGetter.getActiveBoardForUser(boardId, reader) }
    }

    @Test
    fun `Given a missing board, Then listActivePinsForBoard propagates BoardRetrievalBoardDoesNotExistError`() {
        // Given
        every { boardGetter.getActiveBoardForUser(boardId, reader) } throws BoardRetrievalBoardDoesNotExistError()

        // When, Then
        assertThrows<BoardRetrievalBoardDoesNotExistError> {
            useCase.listActivePinsForBoard(reader, boardId, null, 20, sort)
        }
    }

    @Test
    fun `Given a pageSize above the max, Then it is coerced`() {
        // Given
        val page = Page<Pin>(items = emptyList(), previousCursor = null, nextCursor = null)
        every { boardGetter.getActiveBoardForUser(boardId, reader) } returns board
        every {
            pinRepository.findActivePinsForBoard(
                reader = reader,
                boardId = boardId,
                cursor = null,
                pageSize = PinGetter.MAX_PAGE_SIZE,
                sortStrategy = sort,
            )
        } returns page

        // When
        val result = useCase.listActivePinsForBoard(reader, boardId, null, PinGetter.MAX_PAGE_SIZE + 50, sort)

        // Then
        assertSame(page, result)
        verify {
            pinRepository.findActivePinsForBoard(
                reader = reader,
                boardId = boardId,
                cursor = null,
                pageSize = PinGetter.MAX_PAGE_SIZE,
                sortStrategy = sort,
            )
        }
    }
}
