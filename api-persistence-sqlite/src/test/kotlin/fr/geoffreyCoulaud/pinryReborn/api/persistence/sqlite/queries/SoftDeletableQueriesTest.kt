package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.queries

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.RepositoryTest
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QPinBoardModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.BoardRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.PinRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserRepository
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

/**
 * The three query constructors, run against the database for each recyclable type.
 *
 * Boards exercise the shared logic. Pins and users get a test of their own because handing over the
 * wrong column accessor is the one thing a per-type declaration can get wrong.
 */
class SoftDeletableQueriesTest : RepositoryTest() {
    private val userRepository = UserRepository(database)
    private val boardRepository = BoardRepository(database)
    private val pinRepository = PinRepository(database)

    private fun createAndSaveUser(): User =
        userRepository.saveUser(
            User(
                id = randomUUID(),
                name = createRandomString(),
                createdAt = storableNow(),
            ),
        )

    private fun createAndSaveBoard(author: User): Board =
        boardRepository.saveBoard(
            Board(
                id = randomUUID(),
                author = author,
                name = createRandomString(),
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
    fun `Given an active and a recycled board, Then active returns only the active one`() {
        // Given
        val user = createAndSaveUser()
        val activeBoard = createAndSaveBoard(user)
        val recycledBoard = createAndSaveBoard(user)
        boardRepository.softDeleteBoard(recycledBoard, storableNow())

        // When
        val foundIds = BoardQueries.active().findList().map { it.id }

        // Then
        assertEquals(listOf(activeBoard.id), foundIds)
    }

    @Test
    fun `Given an active and a recycled board, Then recycled returns only the recycled one`() {
        // Given
        val user = createAndSaveUser()
        createAndSaveBoard(user)
        val recycledBoard = createAndSaveBoard(user)
        boardRepository.softDeleteBoard(recycledBoard, storableNow())

        // When
        val foundIds = BoardQueries.recycled().findList().map { it.id }

        // Then
        assertEquals(listOf(recycledBoard.id), foundIds)
    }

    @Test
    fun `Given an active and a recycled board, Then any returns both`() {
        // Given
        val user = createAndSaveUser()
        val activeBoard = createAndSaveBoard(user)
        val recycledBoard = createAndSaveBoard(user)
        boardRepository.softDeleteBoard(recycledBoard, storableNow())

        // When
        val foundIds = BoardQueries.any().findList().map { it.id }.toSet()

        // Then
        assertEquals(setOf(activeBoard.id, recycledBoard.id), foundIds)
    }

    @Test
    fun `Given an active and a recycled pin, Then the pin constructors read the pin's own state`() {
        // Given
        val user = createAndSaveUser()
        val activePin = createAndSavePin(user)
        val recycledPin = createAndSavePin(user)
        pinRepository.softDeletePin(recycledPin, storableNow())

        // When
        val activeIds = PinQueries.active().findList().map { it.id }
        val recycledIds = PinQueries.recycled().findList().map { it.id }

        // Then
        assertEquals(listOf(activePin.id), activeIds)
        assertEquals(listOf(recycledPin.id), recycledIds)
    }

    @Test
    fun `Given an active and a tombstoned user, Then the user constructors read the user's own state`() {
        // Given
        val activeUser = createAndSaveUser()
        val tombstonedUser = createAndSaveUser()
        userRepository.markPendingDeletion(tombstonedUser, storableNow())

        // When
        val activeIds = UserQueries.active().findList().map { it.id }
        val tombstonedIds = UserQueries.recycled().findList().map { it.id }

        // Then
        assertEquals(listOf(activeUser.id), activeIds)
        assertEquals(listOf(tombstonedUser.id), tombstonedIds)
    }

    @Test
    fun `Given a membership whose board is recycled, Then withActiveBoard drops it`() {
        // Given
        val user = createAndSaveUser()
        val activeBoard = createAndSaveBoard(user)
        val recycledBoard = createAndSaveBoard(user)
        val pin = createAndSavePin(user, listOf(activeBoard, recycledBoard))
        boardRepository.softDeleteBoard(recycledBoard, storableNow())

        // When
        val boardIds =
            QPinBoardModel()
                .pin.id
                .equalTo(pin.id)
                .withActiveBoard()
                .findList()
                .map { it.board.id }

        // Then
        assertEquals(listOf(activeBoard.id), boardIds)
    }

    @Test
    fun `Given a membership whose pin is recycled, Then withActivePin drops it`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard(user)
        val activePin = createAndSavePin(user, listOf(board))
        val recycledPin = createAndSavePin(user, listOf(board))
        pinRepository.softDeletePin(recycledPin, storableNow())

        // When
        val pinIds =
            QPinBoardModel()
                .board.id
                .equalTo(board.id)
                .withActivePin()
                .findList()
                .map { it.pin.id }

        // Then
        assertEquals(listOf(activePin.id), pinIds)
    }
}
