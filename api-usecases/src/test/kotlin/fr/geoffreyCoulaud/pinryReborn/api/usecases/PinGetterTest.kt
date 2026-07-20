package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.CursorDirection
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinRetrievalPermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinRetrievalPinDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID.randomUUID

class PinGetterTest {
    private val pinRepository = mockk<PinRepositoryInterface>()
    private val useCase = PinGetter(pinRepository = pinRepository)

    @Test
    fun `Given non-existent pin, Then getPinForUser throws`() {
        // Given
        val reader = User(id = randomUUID(), name = createRandomString())
        val pinId = randomUUID()
        every { pinRepository.findPinById(pinId) } returns null

        // When, Then
        assertThrows<PinRetrievalPinDoesNotExistError> {
            useCase.getPinForUser(reader = reader, pinId = pinId)
        }
    }

    @Test
    fun `Given reader reading another user's pin, Then getPinForUser throws`() {
        // Given
        val reader = User(id = randomUUID(), name = createRandomString())
        val author = User(id = randomUUID(), name = createRandomString())
        val pin = Pin(
            id = randomUUID(),
            author = author,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "A pin",
            tags = emptyList(),
            boards = emptyList(),
        )
        every { pinRepository.findPinById(pin.id) } returns pin

        // When, Then
        assertThrows<PinRetrievalPermissionError> {
            useCase.getPinForUser(reader = reader, pinId = pin.id)
        }
    }

    @Test
    fun `Given reader reading their own pin, Then getPinForUser succeeds`() {
        // Given
        val reader = User(id = randomUUID(), name = createRandomString())
        val pin = Pin(
            id = randomUUID(),
            author = reader,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "A pin",
            tags = emptyList(),
            boards = emptyList(),
        )
        every { pinRepository.findPinById(pin.id) } returns pin

        // When
        val result = useCase.getPinForUser(reader = reader, pinId = pin.id)

        // Then
        assertEquals(pin, result)
    }

    @Test
    fun `Given no cursor, Then listPinsPaginatedForUser lists the first page`() {
        // Given
        val reader = User(id = randomUUID(), name = createRandomString())
        val expectedPage = Page<Pin>(items = emptyList(), previousCursor = null, nextCursor = null)
        every {
            pinRepository.findPinsForUser(
                reader = reader,
                cursor = null,
                pageSize = 20,
                sortStrategy = PinSortStrategy.CREATED_AT_ASC,
            )
        } returns expectedPage

        // When
        val result = useCase.listPinsPaginatedForUser(
            reader = reader,
            cursor = null,
            pageSize = 20,
            sort = PinSortStrategy.CREATED_AT_ASC,
        )

        // Then
        assertEquals(expectedPage, result)
    }

    @Test
    fun `Given cursor pointing to reader's own pin, Then listPinsPaginatedForUser lists the next page`() {
        // Given
        val reader = User(id = randomUUID(), name = createRandomString())
        val pin = Pin(
            id = randomUUID(),
            author = reader,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "A pin",
            tags = emptyList(),
            boards = emptyList(),
        )
        val cursor = Cursor(pivotId = pin.id, direction = CursorDirection.FORWARD)
        val expectedPage = Page<Pin>(items = emptyList(), previousCursor = null, nextCursor = null)
        every { pinRepository.findPinById(pin.id) } returns pin
        every {
            pinRepository.findPinsForUser(
                reader = reader,
                cursor = cursor,
                pageSize = 20,
                sortStrategy = PinSortStrategy.CREATED_AT_ASC,
            )
        } returns expectedPage

        // When
        val result = useCase.listPinsPaginatedForUser(
            reader = reader,
            cursor = cursor,
            pageSize = 20,
            sort = PinSortStrategy.CREATED_AT_ASC,
        )

        // Then
        assertEquals(expectedPage, result)
    }
}
