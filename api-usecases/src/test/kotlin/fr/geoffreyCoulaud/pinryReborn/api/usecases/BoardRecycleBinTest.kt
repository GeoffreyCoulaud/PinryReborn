package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BoardDeletionBoardAlreadySoftDeletedError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BoardDeletionBoardDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BoardDeletionBoardNotSoftDeletedError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BoardDeletionPermissionError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID.randomUUID

class BoardRecycleBinTest {
    private val boardRepository: BoardRepositoryInterface = mockk()
    private val useCase = BoardRecycleBin(boardRepository = boardRepository)

    private fun createBoard(author: User, softDeletedAt: Instant? = null) = Board(
        id = randomUUID(),
        author = author,
        name = createRandomString(),
        description = createRandomString(),
        softDeletedAt = softDeletedAt,
    )

    // --- Soft delete ---

    @Test
    fun `Given an owned active board, Then softDelete recycles it`() {
        // Given
        val user = User(id = randomUUID(), name = createRandomString())
        val board = createBoard(author = user)
        val recycled = board.copy(softDeletedAt = Instant.now())
        every { boardRepository.findBoardById(board.id) } returns board
        every { boardRepository.softDeleteBoard(board) } returns recycled

        // When
        val result = useCase.softDelete(boardId = board.id, user = user)

        // Then
        assertEquals(recycled, result)
        verify { boardRepository.softDeleteBoard(board) }
    }

    @Test
    fun `Given an already recycled board, Then softDelete throws BoardDeletionBoardAlreadySoftDeletedError`() {
        // Given
        val user = User(id = randomUUID(), name = createRandomString())
        val board = createBoard(author = user, softDeletedAt = Instant.now())
        every { boardRepository.findBoardById(board.id) } returns board

        // When, Then
        assertThrows<BoardDeletionBoardAlreadySoftDeletedError> {
            useCase.softDelete(boardId = board.id, user = user)
        }
    }

    @Test
    fun `Given a missing board, Then softDelete throws BoardDeletionBoardDoesNotExistError`() {
        // Given
        val user = User(id = randomUUID(), name = createRandomString())
        val boardId = randomUUID()
        every { boardRepository.findBoardById(boardId) } returns null

        // When, Then
        assertThrows<BoardDeletionBoardDoesNotExistError> {
            useCase.softDelete(boardId = boardId, user = user)
        }
    }

    @Test
    fun `Given a board owned by another user, Then softDelete throws BoardDeletionPermissionError`() {
        // Given
        val owner = User(id = randomUUID(), name = createRandomString())
        val otherUser = User(id = randomUUID(), name = createRandomString())
        val board = createBoard(author = owner)
        every { boardRepository.findBoardById(board.id) } returns board

        // When, Then
        assertThrows<BoardDeletionPermissionError> {
            useCase.softDelete(boardId = board.id, user = otherUser)
        }
    }

    // --- Restore ---

    @Test
    fun `Given an owned recycled board, Then restore restores it`() {
        // Given
        val user = User(id = randomUUID(), name = createRandomString())
        val board = createBoard(author = user, softDeletedAt = Instant.now())
        val restored = board.copy(softDeletedAt = null)
        every { boardRepository.findBoardById(board.id) } returns board
        every { boardRepository.restoreBoard(board) } returns restored

        // When
        val result = useCase.restore(boardId = board.id, user = user)

        // Then
        assertEquals(restored, result)
        verify { boardRepository.restoreBoard(board) }
    }

    @Test
    fun `Given a board that is not recycled, Then restore throws BoardDeletionBoardNotSoftDeletedError`() {
        // Given
        val user = User(id = randomUUID(), name = createRandomString())
        val board = createBoard(author = user)
        every { boardRepository.findBoardById(board.id) } returns board

        // When, Then
        assertThrows<BoardDeletionBoardNotSoftDeletedError> {
            useCase.restore(boardId = board.id, user = user)
        }
    }

    @Test
    fun `Given a board owned by another user, Then restore throws BoardDeletionPermissionError`() {
        // Given
        val owner = User(id = randomUUID(), name = createRandomString())
        val otherUser = User(id = randomUUID(), name = createRandomString())
        val board = createBoard(author = owner, softDeletedAt = Instant.now())
        every { boardRepository.findBoardById(board.id) } returns board

        // When, Then
        assertThrows<BoardDeletionPermissionError> {
            useCase.restore(boardId = board.id, user = otherUser)
        }
    }

    // --- Permanent delete ---

    @Test
    fun `Given an owned recycled board, Then permanentlyDelete deletes it`() {
        // Given
        val user = User(id = randomUUID(), name = createRandomString())
        val board = createBoard(author = user, softDeletedAt = Instant.now())
        every { boardRepository.findBoardById(board.id) } returns board
        justRun { boardRepository.permanentlyDeleteBoard(board) }

        // When
        useCase.permanentlyDelete(boardId = board.id, user = user)

        // Then
        verify { boardRepository.permanentlyDeleteBoard(board) }
    }

    @Test
    fun `Given a board that is not recycled, Then permanentlyDelete throws BoardDeletionBoardNotSoftDeletedError`() {
        // Given
        val user = User(id = randomUUID(), name = createRandomString())
        val board = createBoard(author = user)
        every { boardRepository.findBoardById(board.id) } returns board

        // When, Then
        assertThrows<BoardDeletionBoardNotSoftDeletedError> {
            useCase.permanentlyDelete(boardId = board.id, user = user)
        }
    }

    @Test
    fun `Given a board owned by another user, Then permanentlyDelete throws BoardDeletionPermissionError`() {
        // Given
        val owner = User(id = randomUUID(), name = createRandomString())
        val otherUser = User(id = randomUUID(), name = createRandomString())
        val board = createBoard(author = owner, softDeletedAt = Instant.now())
        every { boardRepository.findBoardById(board.id) } returns board

        // When, Then
        assertThrows<BoardDeletionPermissionError> {
            useCase.permanentlyDelete(boardId = board.id, user = otherUser)
        }
    }

    // --- Empty recycle bin ---

    @Test
    fun `When emptyRecycleBin, Then it delegates to permanentlyDeleteAllRecycledBoardsForUser`() {
        // Given
        val user = User(id = randomUUID(), name = createRandomString())
        justRun { boardRepository.permanentlyDeleteAllRecycledBoardsForUser(user) }

        // When
        useCase.emptyRecycleBin(user = user)

        // Then
        verify { boardRepository.permanentlyDeleteAllRecycledBoardsForUser(user) }
    }

    // --- List recycled boards ---

    @Test
    fun `When listRecycledBoardsForUser, Then it delegates to the repository`() {
        // Given
        val user = User(id = randomUUID(), name = createRandomString())
        val board = createBoard(author = user, softDeletedAt = Instant.now())
        val expected = listOf(board)
        every { boardRepository.findRecycledBoardsForUser(user) } returns expected

        // When
        val result = useCase.listRecycledBoardsForUser(user = user)

        // Then
        assertEquals(expected, result)
        verify { boardRepository.findRecycledBoardsForUser(user) }
    }
}
