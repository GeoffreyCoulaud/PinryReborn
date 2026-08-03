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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

/**
 * `getBoardsForPin` deliberately filters out recycled boards from the API view, while
 * `softDeleteBoard` keeps the join row. The export needs the unfiltered read; this suite pins both
 * halves of that contract, split from `PinRepositoryTest` to keep it under detekt's `LargeClass`
 * threshold (mirrors `PinRepositoryPaginationTest`'s precedent for the same split).
 */
class PinRepositoryRecycledMembershipTest : RepositoryTest() {
    private val pinRepository = PinRepository(persistor)
    private val boardRepository = BoardRepository(persistor)
    private val userRepository = UserRepository(persistor)

    private fun createAndSaveUser(): User =
        userRepository.saveUser(
            User(
                id = randomUUID(),
                name = createRandomString(),
                createdAt = storableNow(),
            ),
        )

    private fun createAndSaveBoard(user: User): Board =
        boardRepository.saveBoard(
            Board(
                id = randomUUID(),
                author = user,
                name = createRandomString(),
                description = "",
                createdAt = storableNow(),
                updatedAt = storableNow(),
            ),
        )

    private fun createAndSavePinInBoard(
        user: User,
        board: Board,
    ): Pin =
        pinRepository.savePin(
            Pin(
                id = randomUUID(),
                author = user,
                sourceContextUrl = "https://example.com",
                sourceMediaUrl = "https://example.com/image.jpeg",
                description = "Something",
                tags = emptyList(),
                boards = listOf(board),
                createdAt = storableNow(),
                updatedAt = storableNow(),
            ),
        )

    @Test
    fun `Given a pin in a recycled board, Then the export membership read still sees it`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard(user)
        val pin = createAndSavePinInBoard(user, board)
        boardRepository.softDeleteBoard(board, storableNow())

        // When
        val boards = pinRepository.findBoardsForPinIncludingRecycled(pin.id)

        // Then
        assertEquals(listOf(board.id), boards.map { it.id })
        assertTrue(pinRepository.findPinById(pin.id)!!.boards.isEmpty(), "the API view still filters")
    }
}
