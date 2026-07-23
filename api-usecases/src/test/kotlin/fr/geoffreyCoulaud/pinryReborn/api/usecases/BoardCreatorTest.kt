package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class BoardCreatorTest {
    private val boardRepository: BoardRepositoryInterface = mockk()
    private val clockInstant = Instant.parse("2026-07-23T10:00:00Z")
    private val clock = mockk<Clock> { every { now() } returns clockInstant }
    private val useCase = BoardCreator(boardRepository = boardRepository, clock = clock)

    @Test
    fun `Given valid input, Then create saves a new active board with the given fields`() {
        // Given
        val author = User(id = randomUUID(), name = createRandomString(), createdAt = Instant.now())
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
}
