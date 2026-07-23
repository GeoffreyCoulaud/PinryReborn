package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class PinCreatorTest {
    private val pinRepository: PinRepositoryInterface = mockk()
    private val tagCreator: TagCreator = mockk()
    private val clockInstant = Instant.parse("2026-07-23T10:00:00Z")
    private val clock = mockk<Clock> { every { now() } returns clockInstant }
    private val useCase =
        PinCreator(
            tagCreator = tagCreator,
            pinRepository = pinRepository,
            clock = clock,
        )

    @Test
    fun `When creating a pin, then should succeed`() {
        // Given
        val user = User(randomUUID(), "John Doe", createdAt = Instant.now())
        val sourceUrl = "https://example.com/article"
        val mediaUrl = "https://example.com/image.jpeg"
        val description = "some description"
        val tags = listOf("blue", "landscape", "water")
        every { tagCreator.findOrCreate(any(), any()) } answers {
            Tag(
                id = randomUUID(),
                name = firstArg(),
                author = secondArg(),
                createdAt = Instant.now(),
            )
        }
        every { pinRepository.savePin(any()) } answers { firstArg() }

        // When
        val pin =
            useCase.createPin(
                author = user,
                sourceContextUrl = sourceUrl,
                sourceMediaUrl = mediaUrl,
                description = description,
                tags = tags,
            )

        // Then
        assertEquals(user, pin.author)
        assertEquals(sourceUrl, pin.sourceContextUrl)
        assertEquals(mediaUrl, pin.sourceMediaUrl)
        assertEquals(description, pin.description)
        assertEquals(tags.toSet(), pin.tags.map { it.name }.toSet())
    }
}
