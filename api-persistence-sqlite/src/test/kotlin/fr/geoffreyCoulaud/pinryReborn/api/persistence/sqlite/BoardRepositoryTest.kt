package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.BoardRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.PinRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserRepository
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class BoardRepositoryTest : RepositoryTest() {
    private val boardRepository = BoardRepository(database)
    private val pinRepository = PinRepository(database)
    private val userRepository = UserRepository(database)

    private fun createAndSaveUser(): User =
        userRepository.saveUser(
            User(
                id = randomUUID(),
                name = createRandomString(),
                createdAt = storableNow(),
            ),
        )

    private fun createAndSaveBoard(
        name: String,
        user: User,
    ): Board =
        boardRepository.saveBoard(
            Board(
                id = randomUUID(),
                author = user,
                name = name,
                description = "",
                createdAt = storableNow(),
                updatedAt = storableNow(),
            ),
        )

    private fun createAndSavePin(
        author: User,
        boards: List<Board> = emptyList(),
    ): Pin =
        pinRepository.savePin(
            Pin(
                id = randomUUID(),
                author = author,
                sourceContextUrl = "https://example.com",
                sourceMediaUrl = "https://example.com/image.jpeg",
                description = "Something",
                tags = emptyList(),
                boards = boards,
                createdAt = storableNow(),
                updatedAt = storableNow(),
            ),
        )

    @Test
    fun `Given active boards, Then findActiveBoardsForUser returns them sorted by name case-insensitively`() {
        // Given
        val user = createAndSaveUser()
        createAndSaveBoard("banana", user)
        createAndSaveBoard("Apple", user)
        createAndSaveBoard("cherry", user)

        // When
        val names = boardRepository.findActiveBoardsForUser(user).map { it.name }

        // Then
        assertEquals(listOf("Apple", "banana", "cherry"), names)
    }

    @Test
    fun `Given a soft-deleted board, Then it is excluded from active and included in recycled`() {
        // Given
        val user = createAndSaveUser()
        val activeBoard = createAndSaveBoard("Active", user)
        val recycledBoard = createAndSaveBoard("Recycled", user)

        // When
        val softDeleted = boardRepository.softDeleteBoard(recycledBoard)

        // Then
        assertNotNull(softDeleted.softDeletedAt)
        assertEquals(listOf(activeBoard.id), boardRepository.findActiveBoardsForUser(user).map { it.id })
        assertEquals(listOf(recycledBoard.id), boardRepository.findRecycledBoardsForUser(user).map { it.id })
    }

    @Test
    fun `Given a recycled board, Then restore makes it active again`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard("Board", user)
        val softDeleted = boardRepository.softDeleteBoard(board)

        // When
        val restored = boardRepository.restoreBoard(softDeleted)

        // Then
        assertNull(restored.softDeletedAt)
        assertEquals(listOf(board.id), boardRepository.findActiveBoardsForUser(user).map { it.id })
        assertTrue(boardRepository.findRecycledBoardsForUser(user).isEmpty())
    }

    @Test
    fun `Given a board with active and soft-deleted pins, Then countActivePinsInBoard counts only active`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard("Board", user)
        createAndSavePin(user, boards = listOf(board))
        val softDeletedPin = createAndSavePin(user, boards = listOf(board))
        pinRepository.softDeletePin(softDeletedPin)

        // When
        val count = boardRepository.countActivePinsInBoard(board.id)

        // Then
        assertEquals(1, count)
    }

    @Test
    fun `Given a board with pins, Then permanentlyDeleteBoard removes it and its pin_board rows but leaves the pins`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard("Board", user)
        val pin = createAndSavePin(user, boards = listOf(board))

        // When
        // If the pin_board_model row were not deleted first, this would fail with a foreign
        // key constraint violation (pin_board_model.board_id references boards on delete restrict).
        boardRepository.permanentlyDeleteBoard(board)

        // Then
        assertNull(boardRepository.findActiveBoardById(board.id))
        val reloadedPin = pinRepository.findPinById(pin.id)
        assertNotNull(reloadedPin)
        assertTrue(reloadedPin!!.boards.isEmpty())
    }

    @Test
    fun `Given recycled boards, Then permanentlyDeleteAllRecycledBoardsForUser clears them and their joins`() {
        // Given
        val user = createAndSaveUser()
        val recycledBoard1 = createAndSaveBoard("R1", user)
        val recycledBoard2 = createAndSaveBoard("R2", user)
        val activeBoard = createAndSaveBoard("Active", user)
        val pinInRecycledBoard = createAndSavePin(user, boards = listOf(recycledBoard1))
        boardRepository.softDeleteBoard(recycledBoard1)
        boardRepository.softDeleteBoard(recycledBoard2)

        // When
        // If the pin_board_model rows were not deleted first, this would fail with a foreign
        // key constraint violation (pin_board_model.board_id references boards on delete restrict).
        boardRepository.permanentlyDeleteAllRecycledBoardsForUser(user)

        // Then
        assertNull(boardRepository.findBoardById(recycledBoard1.id))
        assertNull(boardRepository.findBoardById(recycledBoard2.id))
        assertNotNull(boardRepository.findActiveBoardById(activeBoard.id))
        val reloadedPin = pinRepository.findPinById(pinInRecycledBoard.id)
        assertNotNull(reloadedPin)
        assertTrue(reloadedPin!!.boards.isEmpty())
    }

    @Test
    fun `Given no recycled boards, Then permanentlyDeleteAllRecycledBoardsForUser is a no-op`() {
        // Given
        val user = createAndSaveUser()
        val activeBoard = createAndSaveBoard("Board", user)

        // When
        boardRepository.permanentlyDeleteAllRecycledBoardsForUser(user)

        // Then
        assertNotNull(boardRepository.findActiveBoardById(activeBoard.id))
    }

    @Test
    fun `Given active and recycled boards, Then permanentlyDeleteAllBoardsForUser removes all`() {
        // Given
        val user = createAndSaveUser()
        val activeBoard = createAndSaveBoard("Active", user)
        val recycledBoard = createAndSaveBoard("Recycled", user)
        val pinInActiveBoard = createAndSavePin(user, boards = listOf(activeBoard))
        boardRepository.softDeleteBoard(recycledBoard)

        // When
        // If the pin_board_model rows were not deleted first, this would fail with a foreign
        // key constraint violation (pin_board_model.board_id references boards on delete restrict).
        boardRepository.permanentlyDeleteAllBoardsForUser(user)

        // Then
        assertNull(boardRepository.findBoardById(activeBoard.id))
        assertNull(boardRepository.findBoardById(recycledBoard.id))
        val reloadedPin = pinRepository.findPinById(pinInActiveBoard.id)
        assertNotNull(reloadedPin)
        assertTrue(reloadedPin!!.boards.isEmpty())
    }

    @Test
    fun `Given no boards for the user, Then permanentlyDeleteAllBoardsForUser is a no-op`() {
        // Given
        val user = createAndSaveUser()

        // When
        boardRepository.permanentlyDeleteAllBoardsForUser(user)

        // Then
        assertTrue(boardRepository.findActiveBoardsForUser(user).isEmpty())
        assertTrue(boardRepository.findRecycledBoardsForUser(user).isEmpty())
    }

    @Test
    fun `Given an active board, Then findActiveBoardById returns it`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard("Board", user)

        // When
        val result = boardRepository.findActiveBoardById(board.id)

        // Then
        assertEquals(board, result)
    }

    @Test
    fun `Given findActiveBoardById on a recycled board, Then it returns null`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard("Board", user)
        boardRepository.softDeleteBoard(board)

        // When
        val result = boardRepository.findActiveBoardById(board.id)

        // Then
        assertNull(result)
    }

    @Test
    fun `Given an active board, Then findBoardById returns it`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard("Board", user)

        // When
        val result = boardRepository.findBoardById(board.id)

        // Then
        assertEquals(board, result)
    }

    @Test
    fun `Given a recycled board, Then findBoardById still returns it`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard("Board", user)
        boardRepository.softDeleteBoard(board)

        // When
        val result = boardRepository.findBoardById(board.id)

        // Then
        // softDeletedAt is not compared for exact equality: SQLite truncates Instant precision
        // to milliseconds on round-trip, unlike the nanosecond-precision in-memory value.
        assertNotNull(result)
        assertEquals(board.id, result!!.id)
        assertNotNull(result.softDeletedAt)
    }

    @Test
    fun `Given an unknown board id, Then findBoardById returns null`() {
        // Given / When
        val result = boardRepository.findBoardById(randomUUID())

        // Then
        assertNull(result)
    }

    // --- Creation timestamps ---

    @Test
    fun `Given a saved board, Then reading it back exposes its timestamps`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard("Board", user)

        // When
        val found = boardRepository.findBoardById(board.id)

        // Then
        assertNotNull(found?.createdAt)
        assertNotNull(found?.updatedAt)
    }
}
