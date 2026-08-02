package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BoardRetrievalBoardDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BoardRetrievalPermissionError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID.randomUUID

class BoardGetterTest {
    private val boardRepository: BoardRepositoryInterface = mockk()
    private val useCase = BoardGetter(boardRepository = boardRepository)

    @Test
    fun `Given an owned active board, Then getActiveBoardForUser returns it`() {
        // Given
        val reader = User(id = randomUUID(), name = createRandomString(), createdAt = TestTime.now)
        val board = Board(
            id = randomUUID(),
            author = reader,
            name = createRandomString(),
            description = createRandomString(),
            createdAt = TestTime.now,
            updatedAt = TestTime.now,
        )
        every { boardRepository.findActiveBoardById(board.id) } returns board

        // When
        val result = useCase.getActiveBoardForUser(boardId = board.id, reader = reader)

        // Then
        assertEquals(board, result)
    }

    @Test
    fun `Given a missing or recycled board, Then getActiveBoardForUser throws BoardRetrievalBoardDoesNotExistError`() {
        // Given
        val reader = User(id = randomUUID(), name = createRandomString(), createdAt = TestTime.now)
        val boardId = randomUUID()
        every { boardRepository.findActiveBoardById(boardId) } returns null

        // When, Then
        assertThrows<BoardRetrievalBoardDoesNotExistError> {
            useCase.getActiveBoardForUser(boardId = boardId, reader = reader)
        }
    }

    @Test
    fun `Given a board owned by another user, Then getActiveBoardForUser throws BoardRetrievalPermissionError`() {
        // Given
        val reader = User(id = randomUUID(), name = createRandomString(), createdAt = TestTime.now)
        val author = User(id = randomUUID(), name = createRandomString(), createdAt = TestTime.now)
        val board = Board(
            id = randomUUID(),
            author = author,
            name = createRandomString(),
            description = createRandomString(),
            createdAt = TestTime.now,
            updatedAt = TestTime.now,
        )
        every { boardRepository.findActiveBoardById(board.id) } returns board

        // When, Then
        assertThrows<BoardRetrievalPermissionError> {
            useCase.getActiveBoardForUser(boardId = board.id, reader = reader)
        }
    }

    @Test
    fun `Given a reader with boards, Then listActiveBoardsForUser delegates to the repository`() {
        // Given
        val reader = User(id = randomUUID(), name = createRandomString(), createdAt = TestTime.now)
        val board = Board(
            id = randomUUID(),
            author = reader,
            name = createRandomString(),
            description = createRandomString(),
            createdAt = TestTime.now,
            updatedAt = TestTime.now,
        )
        val expected = listOf(board)
        every { boardRepository.findActiveBoardsForUser(reader) } returns expected

        // When
        val result = useCase.listActiveBoardsForUser(reader = reader)

        // Then
        assertEquals(expected, result)
        verify { boardRepository.findActiveBoardsForUser(reader) }
    }

    @Test
    fun `Given an owned active board, Then countActivePinsForUserBoard returns the repository count`() {
        // Given
        val reader = User(id = randomUUID(), name = createRandomString(), createdAt = TestTime.now)
        val board = Board(
            id = randomUUID(),
            author = reader,
            name = createRandomString(),
            description = createRandomString(),
            createdAt = TestTime.now,
            updatedAt = TestTime.now,
        )
        every { boardRepository.findActiveBoardById(board.id) } returns board
        every { boardRepository.countActivePinsInBoard(board.id) } returns 42

        // When
        val count = useCase.countActivePinsForUserBoard(boardId = board.id, reader = reader)

        // Then
        assertEquals(42, count)
        verify { boardRepository.countActivePinsInBoard(board.id) }
    }
}
