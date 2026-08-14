package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.boards.BoardNameAlreadyTakenException
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BoardNameAlreadyExistsError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ErrorCode
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID.randomUUID

class BoardUpdaterTest {
    private val boardRepository: BoardRepositoryInterface = mockk()
    private val boardGetter: BoardGetter = mockk()
    private val clockInstant = Instant.parse("2026-07-23T10:00:00Z")
    private val clock = mockk<Clock> { every { now() } returns clockInstant }
    private val useCase = BoardUpdater(boardRepository = boardRepository, boardGetter = boardGetter, clock = clock)

    @Test
    fun `Given an owned active board, Then update saves a copy with the new name and description`() {
        // Given
        val user = User(id = randomUUID(), name = createRandomString(), createdAt = TestTime.now)
        val board = Board(
            id = randomUUID(),
            author = user,
            name = createRandomString(),
            description = createRandomString(),
            createdAt = TestTime.now,
            updatedAt = TestTime.now,
        )
        val newName = createRandomString()
        val newDescription = createRandomString()
        every { boardGetter.getActiveBoardForUser(boardId = board.id, reader = user) } returns board
        every { boardRepository.saveBoard(any()) } answers { firstArg() }

        // When
        val result = useCase.update(boardId = board.id, name = newName, description = newDescription, user = user)

        // Then
        assertEquals(board.id, result.id)
        assertEquals(newName, result.name)
        assertEquals(newDescription, result.description)
        assertEquals(clockInstant, result.updatedAt)
        assertEquals(board.createdAt, result.createdAt)
        verify {
            boardRepository.saveBoard(
                board.copy(name = newName, description = newDescription, updatedAt = clockInstant),
            )
        }
    }

    @Test
    fun `Given the new name held by another board, Then update rethrows BoardNameAlreadyExistsError`() {
        // Given: renaming is the second of the three sites the index refuses
        val user = User(id = randomUUID(), name = createRandomString(), createdAt = TestTime.now)
        val board = Board(
            id = randomUUID(),
            author = user,
            name = createRandomString(),
            description = createRandomString(),
            createdAt = TestTime.now,
            updatedAt = TestTime.now,
        )
        val takenName = createRandomString()
        every { boardGetter.getActiveBoardForUser(boardId = board.id, reader = user) } returns board
        every { boardRepository.saveBoard(any()) } throws BoardNameAlreadyTakenException(cause = Exception("boom"))
        every { boardRepository.findBoardForUserByName(user = user, name = takenName) } returns
            board.copy(id = randomUUID(), name = takenName)

        // When
        val error = assertThrows<BoardNameAlreadyExistsError> {
            useCase.update(boardId = board.id, name = takenName, description = createRandomString(), user = user)
        }

        // Then
        assertEquals(ErrorCode.BOARD_NAME_ALREADY_EXISTS, error.code)
    }
}
