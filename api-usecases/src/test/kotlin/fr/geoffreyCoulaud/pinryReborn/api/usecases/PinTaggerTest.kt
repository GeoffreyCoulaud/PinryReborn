package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinTaggingPermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinTaggingPinDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinTaggingSoftDeletedPinError
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID.randomUUID

class PinTaggerTest {
    private val tagCreator = mockk<TagCreator>()
    private val pinRepository = mockk<PinRepositoryInterface>()
    private val clockInstant = Instant.parse("2026-07-23T10:00:00Z")
    private val clock = mockk<Clock> { every { now() } returns clockInstant }
    private val useCase = PinTagger(tagCreator = tagCreator, pinRepository = pinRepository, clock = clock)

    @Test
    fun `Setting tags replaces existing tags`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val existingTag = Tag(id = randomUUID(), name = "oldtag", author = user, createdAt = Instant.now())
        val pin = Pin(
            id = randomUUID(),
            author = user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "A pin",
            tags = listOf(existingTag),
            boards = emptyList(),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        val newTagNames = listOf("newtag1", "newtag2")
        val newTag1 = Tag(id = randomUUID(), name = "newtag1", author = user, createdAt = Instant.now())
        val newTag2 = Tag(id = randomUUID(), name = "newtag2", author = user, createdAt = Instant.now())

        every { pinRepository.findPinById(pin.id) } returns pin
        every { tagCreator.findOrCreate(name = "newtag1", user = user) } returns newTag1
        every { tagCreator.findOrCreate(name = "newtag2", user = user) } returns newTag2
        every { pinRepository.savePin(any()) } answers { firstArg() }

        // When
        val result = useCase.setTags(pinId = pin.id, tagNames = newTagNames, user = user)

        // Then
        assertEquals(listOf(newTag1, newTag2), result.tags)
    }

    @Test
    fun `Setting empty list clears all tags`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val existingTag = Tag(id = randomUUID(), name = "oldtag", author = user, createdAt = Instant.now())
        val pin = Pin(
            id = randomUUID(),
            author = user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "A pin",
            tags = listOf(existingTag),
            boards = emptyList(),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

        every { pinRepository.findPinById(pin.id) } returns pin
        every { pinRepository.savePin(any()) } answers { firstArg() }

        // When
        val result = useCase.setTags(pinId = pin.id, tagNames = emptyList(), user = user)

        // Then
        assertEquals(emptyList<Tag>(), result.tags)
    }

    @Test
    fun `Setting same tags is idempotent`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val existingTag = Tag(id = randomUUID(), name = "sametag", author = user, createdAt = Instant.now())
        val pin = Pin(
            id = randomUUID(),
            author = user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "A pin",
            tags = listOf(existingTag),
            boards = emptyList(),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

        every { pinRepository.findPinById(pin.id) } returns pin
        every { tagCreator.findOrCreate(name = "sametag", user = user) } returns existingTag
        every { pinRepository.savePin(any()) } answers { firstArg() }

        // When
        val result = useCase.setTags(pinId = pin.id, tagNames = listOf("sametag"), user = user)

        // Then
        assertEquals(listOf(existingTag), result.tags)
    }

    @Test
    fun `Setting tags on non-existent pin throws error`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val nonExistentPinId = randomUUID()

        every { pinRepository.findPinById(nonExistentPinId) } returns null

        // When, Then
        assertThrows<PinTaggingPinDoesNotExistError> {
            useCase.setTags(pinId = nonExistentPinId, tagNames = listOf("tag"), user = user)
        }
    }

    @Test
    fun `Setting tags on another user's pin throws permission error`() {
        // Given
        val owner = User(id = randomUUID(), name = "Owner", createdAt = Instant.now())
        val otherUser = User(id = randomUUID(), name = "Other", createdAt = Instant.now())
        val pin = Pin(
            id = randomUUID(),
            author = owner,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "A pin",
            tags = emptyList(),
            boards = emptyList(),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

        every { pinRepository.findPinById(pin.id) } returns pin

        // When, Then
        assertThrows<PinTaggingPermissionError> {
            useCase.setTags(pinId = pin.id, tagNames = listOf("tag"), user = otherUser)
        }
    }

    @Test
    fun `Given soft-deleted pin, Then throws PinTaggingSoftDeletedPinError`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val pin = Pin(
            id = randomUUID(),
            author = user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "A pin",
            tags = emptyList(),
            boards = emptyList(),
            softDeletedAt = Instant.now(),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

        every { pinRepository.findPinById(pin.id) } returns pin

        // When, Then
        assertThrows<PinTaggingSoftDeletedPinError> {
            useCase.setTags(pinId = pin.id, tagNames = listOf("tag"), user = user)
        }
    }
}
