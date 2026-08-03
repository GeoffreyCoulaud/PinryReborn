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
import org.junit.jupiter.api.assertThrows
import java.util.UUID.randomUUID

class BoardRepositoryTest : RepositoryTest() {
    private val boardRepository = BoardRepository(persistor)
    private val pinRepository = PinRepository(persistor)
    private val userRepository = UserRepository(persistor)

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

    // A board whose id is not in the store: the use case read and validated a board a concurrent
    // hard delete has since removed. Absence at the transition is an illegal state.
    private fun absentBoard(user: User): Board =
        Board(
            id = randomUUID(),
            author = user,
            name = "Ghost",
            description = "",
            createdAt = storableNow(),
            updatedAt = storableNow(),
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
        val softDeleted = boardRepository.softDeleteBoard(recycledBoard, storableNow())

        // Then
        assertNotNull(softDeleted.softDeletedAt)
        assertEquals(listOf(activeBoard.id), boardRepository.findActiveBoardsForUser(user).map { it.id })
        assertEquals(listOf(recycledBoard.id), boardRepository.findRecycledBoardsForUser(user).map { it.id })
    }

    @Test
    fun `Given a deletion instant, Then softDeleteBoard stores it as both softDeletedAt and updatedAt`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard("Board", user)
        val deletionInstant = Instant.parse("2026-01-02T03:04:05Z")

        // When
        boardRepository.softDeleteBoard(board, deletionInstant)

        // Then - the instant reaches the columns unchanged, read back from the store
        val stored = requireNotNull(boardRepository.findBoardById(board.id))
        assertEquals(deletionInstant, stored.softDeletedAt)
        assertEquals(deletionInstant, stored.updatedAt)
    }

    @Test
    fun `Given a recycled board, Then restore makes it active again`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard("Board", user)
        val softDeleted = boardRepository.softDeleteBoard(board, storableNow())

        // When
        val restored = boardRepository.restoreBoard(softDeleted, storableNow())

        // Then
        assertNull(restored.softDeletedAt)
        assertEquals(listOf(board.id), boardRepository.findActiveBoardsForUser(user).map { it.id })
        assertTrue(boardRepository.findRecycledBoardsForUser(user).isEmpty())
    }

    @Test
    fun `Given a restoration instant, Then restoreBoard stores it as updatedAt and clears softDeletedAt`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard("Board", user)
        val softDeleted = boardRepository.softDeleteBoard(board, storableNow())
        val restorationInstant = Instant.parse("2026-02-03T04:05:06Z")

        // When
        boardRepository.restoreBoard(softDeleted, restorationInstant)

        // Then - the instant reaches the column unchanged, read back from the store
        val stored = requireNotNull(boardRepository.findBoardById(board.id))
        assertEquals(restorationInstant, stored.updatedAt)
        assertNull(stored.softDeletedAt)
    }

    @Test
    fun `Given a board absent from the store, Then softDeleteBoard throws IllegalStateException naming its id`() {
        // Given - absence at the transition is an illegal state (concurrent hard delete), not a
        // missing argument, so it throws rather than NPE-ing on the null model
        val user = createAndSaveUser()
        val board = absentBoard(user)

        // When / Then
        val exception = assertThrows<IllegalStateException> {
            boardRepository.softDeleteBoard(board, storableNow())
        }
        assertTrue(exception.message!!.contains(board.id.toString()))
    }

    @Test
    fun `Given a board absent from the store, Then restoreBoard throws IllegalStateException naming its id`() {
        // Given - same illegal-state condition as softDeleteBoard on an absent row
        val user = createAndSaveUser()
        val board = absentBoard(user)

        // When / Then
        val exception = assertThrows<IllegalStateException> {
            boardRepository.restoreBoard(board, storableNow())
        }
        assertTrue(exception.message!!.contains(board.id.toString()))
    }

    @Test
    fun `Given a board with active and soft-deleted pins, Then countActivePinsInBoard counts only active`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard("Board", user)
        createAndSavePin(user, boards = listOf(board))
        val softDeletedPin = createAndSavePin(user, boards = listOf(board))
        pinRepository.softDeletePin(softDeletedPin, storableNow())

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
        boardRepository.softDeleteBoard(recycledBoard1, storableNow())
        boardRepository.softDeleteBoard(recycledBoard2, storableNow())

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
        boardRepository.softDeleteBoard(recycledBoard, storableNow())

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
        boardRepository.softDeleteBoard(board, storableNow())

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
        boardRepository.softDeleteBoard(board, storableNow())

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
