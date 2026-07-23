package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
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
        val user = User(id = randomUUID(), name = createRandomString(), createdAt = Instant.now())
        val board = Board(
            id = randomUUID(),
            author = user,
            name = createRandomString(),
            description = createRandomString(),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
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
}
