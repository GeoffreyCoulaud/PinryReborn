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
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID.randomUUID

class PinRecycleBinGetterTest {
    private val pinRepository = mockk<PinRepositoryInterface>()
    private val pinGetter = PinGetter(pinRepository = pinRepository)
    private val useCase = PinRecycleBinGetter(pinRepository = pinRepository, pinGetter = pinGetter)

    @Test
    fun `Given user with soft-deleted pins, Then list returns them paginated`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val expectedPage = Page<Pin>(items = emptyList(), previousCursor = null, nextCursor = null)
        every {
            pinRepository.findSoftDeletedPinsForUser(
                reader = user,
                cursor = null,
                pageSize = 20,
                sortStrategy = PinSortStrategy.CREATED_AT_ASC,
            )
        } returns expectedPage

        // When
        val result = useCase.listSoftDeletedPinsPaginatedForUser(
            reader = user,
            cursor = null,
            pageSize = 20,
            sort = PinSortStrategy.CREATED_AT_ASC,
        )

        // Then
        assertEquals(expectedPage, result)
    }

    @Test
    fun `Given page size exceeds max, Then it is capped`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val expectedPage = Page<Pin>(items = emptyList(), previousCursor = null, nextCursor = null)
        every {
            pinRepository.findSoftDeletedPinsForUser(
                reader = user,
                cursor = null,
                pageSize = 100,
                sortStrategy = PinSortStrategy.CREATED_AT_ASC,
            )
        } returns expectedPage

        // When
        val result = useCase.listSoftDeletedPinsPaginatedForUser(
            reader = user,
            cursor = null,
            pageSize = 500,
            sort = PinSortStrategy.CREATED_AT_ASC,
        )

        // Then
        assertEquals(expectedPage, result)
    }

    @Test
    fun `Given DELETED_AT_DESC sort, Then sort strategy is passed to repository`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val expectedPage = Page<Pin>(items = emptyList(), previousCursor = null, nextCursor = null)
        every {
            pinRepository.findSoftDeletedPinsForUser(
                reader = user,
                cursor = null,
                pageSize = 20,
                sortStrategy = PinSortStrategy.DELETED_AT_DESC,
            )
        } returns expectedPage

        // When
        val result = useCase.listSoftDeletedPinsPaginatedForUser(
            reader = user,
            cursor = null,
            pageSize = 20,
            sort = PinSortStrategy.DELETED_AT_DESC,
        )

        // Then
        assertEquals(expectedPage, result)
    }

    @Test
    fun `Given cursor pointing to another user's pin, Then throws permission error`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val otherUser = User(id = randomUUID(), name = "Other", createdAt = Instant.now())
        val pin = Pin(
            id = randomUUID(),
            author = otherUser,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "A pin",
            tags = emptyList(),
            boards = emptyList(),
            softDeletedAt = Instant.now(),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        val cursor = Cursor(pivotId = pin.id, direction = CursorDirection.FORWARD)
        every { pinRepository.findPinById(pin.id) } returns pin

        // When, Then
        assertThrows<PinRetrievalPermissionError> {
            useCase.listSoftDeletedPinsPaginatedForUser(
                reader = user,
                cursor = cursor,
                pageSize = 20,
                sort = PinSortStrategy.CREATED_AT_ASC,
            )
        }
    }

    @Test
    fun `Given cursor pointing to non-existent pin, Then throws not found error`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val cursor = Cursor(pivotId = randomUUID(), direction = CursorDirection.FORWARD)
        every { pinRepository.findPinById(cursor.pivotId) } returns null

        // When, Then
        assertThrows<PinRetrievalPinDoesNotExistError> {
            useCase.listSoftDeletedPinsPaginatedForUser(
                reader = user,
                cursor = cursor,
                pageSize = 20,
                sort = PinSortStrategy.CREATED_AT_ASC,
            )
        }
    }
}
