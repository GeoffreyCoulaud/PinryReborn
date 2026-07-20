package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinBoardSettingInvalidBoardError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinBoardSettingPermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinBoardSettingPinDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinBoardSettingSoftDeletedPinError
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID.randomUUID

class PinBoardSetterTest {
    private val pinRepository = mockk<PinRepositoryInterface>()
    private val boardRepository = mockk<BoardRepositoryInterface>()
    private val useCase = PinBoardSetter(pinRepository = pinRepository, boardRepository = boardRepository)

    @Test
    fun `Given an owned active pin and valid owned boards, Then setBoards replaces the pin's boards`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe")
        val oldBoard = Board(id = randomUUID(), author = user, name = "Old", description = "")
        val pin = Pin(
            id = randomUUID(),
            author = user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "A pin",
            tags = emptyList(),
            boards = listOf(oldBoard),
        )
        val newBoard1 = Board(id = randomUUID(), author = user, name = "New 1", description = "")
        val newBoard2 = Board(id = randomUUID(), author = user, name = "New 2", description = "")

        every { pinRepository.findPinById(pin.id) } returns pin
        every { boardRepository.findActiveBoardById(newBoard1.id) } returns newBoard1
        every { boardRepository.findActiveBoardById(newBoard2.id) } returns newBoard2
        every { pinRepository.savePin(any()) } answers { firstArg() }

        // When
        val result = useCase.setBoards(
            pinId = pin.id,
            boardIds = listOf(newBoard1.id, newBoard2.id),
            user = user,
        )

        // Then
        assertEquals(listOf(newBoard1, newBoard2), result.boards)
    }

    @Test
    fun `Given a missing pin, Then throws PinBoardSettingPinDoesNotExistError`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe")
        val nonExistentPinId = randomUUID()

        every { pinRepository.findPinById(nonExistentPinId) } returns null

        // When, Then
        assertThrows<PinBoardSettingPinDoesNotExistError> {
            useCase.setBoards(pinId = nonExistentPinId, boardIds = emptyList(), user = user)
        }
    }

    @Test
    fun `Given a pin owned by another user, Then throws PinBoardSettingPermissionError`() {
        // Given
        val owner = User(id = randomUUID(), name = "Owner")
        val otherUser = User(id = randomUUID(), name = "Other")
        val pin = Pin(
            id = randomUUID(),
            author = owner,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "A pin",
            tags = emptyList(),
            boards = emptyList(),
        )

        every { pinRepository.findPinById(pin.id) } returns pin

        // When, Then
        assertThrows<PinBoardSettingPermissionError> {
            useCase.setBoards(pinId = pin.id, boardIds = emptyList(), user = otherUser)
        }
    }

    @Test
    fun `Given a soft-deleted pin, Then throws PinBoardSettingSoftDeletedPinError`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe")
        val pin = Pin(
            id = randomUUID(),
            author = user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "A pin",
            tags = emptyList(),
            boards = emptyList(),
            softDeletedAt = Instant.now(),
        )

        every { pinRepository.findPinById(pin.id) } returns pin

        // When, Then
        assertThrows<PinBoardSettingSoftDeletedPinError> {
            useCase.setBoards(pinId = pin.id, boardIds = emptyList(), user = user)
        }
    }

    @Test
    fun `Given an unresolved boardId, Then throws PinBoardSettingInvalidBoardError and saves nothing`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe")
        val pin = Pin(
            id = randomUUID(),
            author = user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "A pin",
            tags = emptyList(),
            boards = emptyList(),
        )
        val badBoardId = randomUUID()

        every { pinRepository.findPinById(pin.id) } returns pin
        every { boardRepository.findActiveBoardById(badBoardId) } returns null

        // When, Then
        assertThrows<PinBoardSettingInvalidBoardError> {
            useCase.setBoards(pinId = pin.id, boardIds = listOf(badBoardId), user = user)
        }
        verify(exactly = 0) { pinRepository.savePin(any()) }
    }

    @Test
    fun `Given a board owned by another user, Then throws PinBoardSettingInvalidBoardError`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe")
        val otherUser = User(id = randomUUID(), name = "Other")
        val pin = Pin(
            id = randomUUID(),
            author = user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "A pin",
            tags = emptyList(),
            boards = emptyList(),
        )
        val othersBoard = Board(id = randomUUID(), author = otherUser, name = "Not yours", description = "")

        every { pinRepository.findPinById(pin.id) } returns pin
        every { boardRepository.findActiveBoardById(othersBoard.id) } returns othersBoard

        // When, Then
        assertThrows<PinBoardSettingInvalidBoardError> {
            useCase.setBoards(pinId = pin.id, boardIds = listOf(othersBoard.id), user = user)
        }
        verify(exactly = 0) { pinRepository.savePin(any()) }
    }

    @Test
    fun `Given an empty boardIds list, Then the pin's boards are cleared`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe")
        val existingBoard = Board(id = randomUUID(), author = user, name = "Old", description = "")
        val pin = Pin(
            id = randomUUID(),
            author = user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "A pin",
            tags = emptyList(),
            boards = listOf(existingBoard),
        )

        every { pinRepository.findPinById(pin.id) } returns pin
        every { pinRepository.savePin(any()) } answers { firstArg() }

        // When
        val result = useCase.setBoards(pinId = pin.id, boardIds = emptyList(), user = user)

        // Then
        assertEquals(emptyList<Board>(), result.boards)
    }
}
