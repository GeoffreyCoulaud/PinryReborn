package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SearchEmptyQueryError
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID.randomUUID

class PinSearcherTest {
    private val pinRepository = mockk<PinRepositoryInterface>()
    private val useCase = PinSearcher(pinRepository = pinRepository)

    private fun createUser() = User(id = randomUUID(), name = "John Doe")

    private fun createPin(user: User, description: String) = Pin(
        id = randomUUID(),
        author = user,
        sourceContextUrl = "https://example.com/page",
        sourceMediaUrl = "https://example.com/image.jpg",
        description = description,
        tags = emptyList(),
        boards = emptyList()
    )

    @Test
    fun `Given empty query, Then throws SearchEmptyQueryError`() {
        // Given
        val user = createUser()

        // When, Then
        assertThrows<SearchEmptyQueryError> {
            useCase.searchPins(user = user, query = "", limit = 10)
        }
    }

    @Test
    fun `Given blank query, Then throws SearchEmptyQueryError`() {
        // Given
        val user = createUser()

        // When, Then
        assertThrows<SearchEmptyQueryError> {
            useCase.searchPins(user = user, query = "   ", limit = 10)
        }
    }

    @Test
    fun `Given no pins, Then returns empty list`() {
        // Given
        val user = createUser()
        every { pinRepository.findAllPinsForUser(user) } returns emptyList()

        // When
        val results = useCase.searchPins(user = user, query = "test", limit = 10)

        // Then
        assertTrue(results.isEmpty())
    }

    @Test
    fun `Given exact match in description, Then returns pin with high score`() {
        // Given
        val user = createUser()
        val pin = createPin(user, "mountain")
        every { pinRepository.findAllPinsForUser(user) } returns listOf(pin)

        // When
        val results = useCase.searchPins(user = user, query = "mountain", limit = 10)

        // Then
        assertEquals(1, results.size)
        assertEquals("mountain", results[0].item.description)
        assertTrue(results[0].score > 0.8)
    }

    @Test
    fun `Given multiple pins, Then returns results sorted by score descending`() {
        // Given
        val user = createUser()
        val pins = listOf(
            createPin(user, "Beautiful mountain landscape"),
            createPin(user, "Mountain view from cabin"),
            createPin(user, "City skyline at night")
        )
        every { pinRepository.findAllPinsForUser(user) } returns pins

        // When
        val results = useCase.searchPins(user = user, query = "mountain", limit = 10)

        // Then
        assertTrue(results.size >= 2)
        // Results should be sorted by descending score
        for (i in 0 until results.size - 1) {
            assertTrue(results[i].score >= results[i + 1].score)
        }
    }

    @Test
    fun `Given limit parameter, Then returns at most limit results`() {
        // Given
        val user = createUser()
        val pins = listOf(
            createPin(user, "test pin 1"),
            createPin(user, "test pin 2"),
            createPin(user, "test pin 3"),
            createPin(user, "test pin 4"),
            createPin(user, "test pin 5")
        )
        every { pinRepository.findAllPinsForUser(user) } returns pins

        // When
        val results = useCase.searchPins(user = user, query = "test", limit = 2)

        // Then
        assertEquals(2, results.size)
    }

    @Test
    fun `Given low score results, Then filters them out`() {
        // Given
        val user = createUser()
        val pins = listOf(
            createPin(user, "landscape"),
            createPin(user, "xyz")
        )
        every { pinRepository.findAllPinsForUser(user) } returns pins

        // When
        val results = useCase.searchPins(user = user, query = "landscape", limit = 10)

        // Then
        assertEquals(1, results.size)
        assertEquals("landscape", results[0].item.description)
    }

    @Test
    fun `Given typo in query, Then still finds matching pin`() {
        // Given
        val user = createUser()
        val pin = createPin(user, "Mountain peak at sunset")
        every { pinRepository.findAllPinsForUser(user) } returns listOf(pin)

        // When
        val results = useCase.searchPins(user = user, query = "mountan", limit = 10)

        // Then
        assertEquals(1, results.size)
    }

    @Test
    fun `Given case-insensitive query, Then matches pins regardless of case`() {
        // Given
        val user = createUser()
        val pin = createPin(user, "LANDSCAPE")
        every { pinRepository.findAllPinsForUser(user) } returns listOf(pin)

        // When
        val results = useCase.searchPins(user = user, query = "landscape", limit = 10)

        // Then
        assertEquals(1, results.size)
    }

    @Test
    fun `Given query matching partial description, Then returns pin`() {
        // Given
        val user = createUser()
        val pin = createPin(user, "Beautiful mountain landscape at sunrise")
        every { pinRepository.findAllPinsForUser(user) } returns listOf(pin)

        // When
        val results = useCase.searchPins(user = user, query = "mountain", limit = 10)

        // Then
        assertEquals(1, results.size)
    }
}
