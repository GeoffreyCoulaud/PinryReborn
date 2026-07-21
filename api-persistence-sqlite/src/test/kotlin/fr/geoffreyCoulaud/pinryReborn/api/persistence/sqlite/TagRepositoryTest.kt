package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.PinRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.TagRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserRepository
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class TagRepositoryTest : RepositoryTest() {
    private val repository = TagRepository(database)
    private val userRepository = UserRepository(database)
    private val pinRepository = PinRepository(database)

    private fun createAndSaveUser(): User =
        userRepository.saveUser(
            User(id = randomUUID(), name = createRandomString()),
        )

    private fun createPinWithTag(
        author: User,
        tag: Tag,
    ): Pin =
        pinRepository.savePin(
            Pin(
                id = randomUUID(),
                author = author,
                sourceContextUrl = "https://example.com",
                sourceMediaUrl = "https://example.com/image.jpeg",
                description = "Something",
                tags = listOf(tag),
                boards = emptyList(),
            ),
        )

    @Test
    fun `Given a new tag, Then saveTag persists it`() {
        // Given
        val user = createAndSaveUser()
        val tag = Tag(id = randomUUID(), author = user, name = "landscape")

        // When
        val saved = repository.saveTag(tag)

        // Then
        assertEquals(tag.id, saved.id)
        assertEquals(tag.name, saved.name)
    }

    @Test
    fun `Given a tag owned by the user, Then findUserTagByName returns it`() {
        // Given
        val user = createAndSaveUser()
        val tag = repository.saveTag(Tag(id = randomUUID(), author = user, name = "landscape"))

        // When
        val found = repository.findUserTagByName(user = user, name = "landscape")

        // Then
        assertNotNull(found)
        assertEquals(tag.id, found!!.id)
    }

    @Test
    fun `Given no tag with the given name, Then findUserTagByName returns null`() {
        // Given
        val user = createAndSaveUser()

        // When
        val found = repository.findUserTagByName(user = user, name = "nonexistent")

        // Then
        assertNull(found)
    }

    @Test
    fun `Given tags owned by the user, Then findAllTagsForUser returns them`() {
        // Given
        val user = createAndSaveUser()
        val tag1 = repository.saveTag(Tag(id = randomUUID(), author = user, name = "tag1"))
        val tag2 = repository.saveTag(Tag(id = randomUUID(), author = user, name = "tag2"))

        // When
        val tags = repository.findAllTagsForUser(user)

        // Then
        assertEquals(setOf(tag1, tag2), tags.toSet())
    }

    @Test
    fun `Given tags owned by the user, Then deleteAllTagsForUser removes them`() {
        // Given
        val user = createAndSaveUser()
        repository.saveTag(Tag(id = randomUUID(), author = user, name = "tag1"))
        repository.saveTag(Tag(id = randomUUID(), author = user, name = "tag2"))

        // When
        repository.deleteAllTagsForUser(user)

        // Then
        assertEquals(emptyList<Tag>(), repository.findAllTagsForUser(user))
    }

    @Test
    fun `Given a tag used by a pin, Then deleteAllTagsForUser removes it and its pin_tag junction row`() {
        // Given
        val user = createAndSaveUser()
        val tag = repository.saveTag(Tag(id = randomUUID(), author = user, name = "tag1"))
        val pin = createPinWithTag(author = user, tag = tag)

        // When
        // If the pin_tag junction row were not deleted first, this would fail with a foreign
        // key constraint violation (pin_tag_model.tag_id references tags on delete restrict).
        repository.deleteAllTagsForUser(user)

        // Then
        assertEquals(emptyList<Tag>(), repository.findAllTagsForUser(user))
        val reloadedPin = pinRepository.findPinById(pin.id)
        assertNotNull(reloadedPin)
        assertTrue(reloadedPin!!.tags.isEmpty())
    }

    @Test
    fun `Given a user with no tags, Then deleteAllTagsForUser is a no-op`() {
        // Given
        val user = createAndSaveUser()

        // When
        repository.deleteAllTagsForUser(user)

        // Then
        assertEquals(emptyList<Tag>(), repository.findAllTagsForUser(user))
    }
}
