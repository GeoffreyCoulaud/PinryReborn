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
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID.randomUUID

class BoardCreatorTest {
    private val boardRepository: BoardRepositoryInterface = mockk()
    private val clockInstant = Instant.parse("2026-07-23T10:00:00Z")
    private val clock = mockk<Clock> { every { now() } returns clockInstant }
    private val useCase = BoardCreator(boardRepository = boardRepository, clock = clock)

    @Test
    fun `Given valid input, Then create saves a new active board with the given fields`() {
        // Given
        val author = User(id = randomUUID(), name = createRandomString(), createdAt = TestTime.now)
        val name = createRandomString()
        val description = createRandomString()
        every { boardRepository.saveBoard(any()) } answers { firstArg() }

        // When
        val board = useCase.create(author = author, name = name, description = description)

        // Then
        assertEquals(author, board.author)
        assertEquals(name, board.name)
        assertEquals(description, board.description)
        assertNull(board.softDeletedAt)
    }

    @Test
    fun `Given the name held by an active board, Then create rethrows BoardNameAlreadyExistsError`() {
        // Given: the index is the authority, so the refusal arrives from the store
        val author = User(id = randomUUID(), name = createRandomString(), createdAt = TestTime.now)
        val name = createRandomString()
        val holder = board(author = author, name = name)
        every { boardRepository.saveBoard(any()) } throws BoardNameAlreadyTakenException(cause = Exception("boom"))
        every { boardRepository.findBoardForUserByName(user = author, name = name) } returns holder

        // When
        val error = assertThrows<BoardNameAlreadyExistsError> {
            useCase.create(author = author, name = name, description = createRandomString())
        }

        // Then
        assertEquals(ErrorCode.BOARD_NAME_ALREADY_EXISTS, error.code)
        assertFalse(error.message.orEmpty().contains(RECYCLE_BIN_WORDING))
    }

    @Test
    fun `Given the name held by a recycled board, Then the refusal says the recycle bin holds it`() {
        // Given: the index covers every row, so a client with an empty board list needs telling why
        val author = User(id = randomUUID(), name = createRandomString(), createdAt = TestTime.now)
        val name = createRandomString()
        val holder = board(author = author, name = name).copy(softDeletedAt = TestTime.now)
        every { boardRepository.saveBoard(any()) } throws BoardNameAlreadyTakenException(cause = Exception("boom"))
        every { boardRepository.findBoardForUserByName(user = author, name = name) } returns holder

        // When
        val error = assertThrows<BoardNameAlreadyExistsError> {
            useCase.create(author = author, name = name, description = createRandomString())
        }

        // Then
        assertTrue(error.message.orEmpty().contains(RECYCLE_BIN_WORDING))
    }

    @Test
    fun `Given the holder hard-deleted before it is read back, Then create still rethrows the collision`() {
        // Given: a concurrent empty-the-bin between the violation and the lookup that explains it
        val author = User(id = randomUUID(), name = createRandomString(), createdAt = TestTime.now)
        val name = createRandomString()
        every { boardRepository.saveBoard(any()) } throws BoardNameAlreadyTakenException(cause = Exception("boom"))
        every { boardRepository.findBoardForUserByName(user = author, name = name) } returns null

        // When
        val error = assertThrows<BoardNameAlreadyExistsError> {
            useCase.create(author = author, name = name, description = createRandomString())
        }

        // Then
        assertEquals(ErrorCode.BOARD_NAME_ALREADY_EXISTS, error.code)
        assertFalse(error.message.orEmpty().contains(RECYCLE_BIN_WORDING))
    }

    private fun board(author: User, name: String) =
        Board(
            id = randomUUID(),
            author = author,
            name = name,
            description = createRandomString(),
            createdAt = TestTime.now,
            updatedAt = TestTime.now,
        )

    private companion object {
        /** The wording the presentation layer hands the client; asserted, not the whole sentence. */
        const val RECYCLE_BIN_WORDING = "recycle bin"
    }
}
