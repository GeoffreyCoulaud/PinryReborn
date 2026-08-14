package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.PinRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.TagRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserRepository
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import jakarta.persistence.PersistenceException
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID.randomUUID

class TagRepositoryTest : RepositoryTest() {
    private val repository = TagRepository(persistor)
    private val userRepository = UserRepository(persistor)
    private val pinRepository = PinRepository(persistor)

    private fun createAndSaveUser(): User =
        userRepository.saveUser(
            User(id = randomUUID(), name = createRandomString(), createdAt = storableNow()),
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
                createdAt = storableNow(),
                updatedAt = storableNow(),
            ),
        )

    @Test
    fun `Given a new tag, Then saveTag persists it`() {
        // Given
        val user = createAndSaveUser()
        val tag = Tag(id = randomUUID(), author = user, name = "landscape", createdAt = storableNow())

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
        val tag =
            repository.saveTag(
                Tag(id = randomUUID(), author = user, name = "landscape", createdAt = storableNow()),
            )

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
        val tag1 = repository.saveTag(Tag(id = randomUUID(), author = user, name = "tag1", createdAt = storableNow()))
        val tag2 = repository.saveTag(Tag(id = randomUUID(), author = user, name = "tag2", createdAt = storableNow()))

        // When
        val tags = repository.findAllTagsForUser(user)

        // Then
        assertEquals(setOf(tag1, tag2), tags.toSet())
    }

    @Test
    fun `Given tags owned by the user, Then deleteAllTagsForUser removes them`() {
        // Given
        val user = createAndSaveUser()
        repository.saveTag(Tag(id = randomUUID(), author = user, name = "tag1", createdAt = storableNow()))
        repository.saveTag(Tag(id = randomUUID(), author = user, name = "tag2", createdAt = storableNow()))

        // When
        repository.deleteAllTagsForUser(user)

        // Then
        assertEquals(emptyList<Tag>(), repository.findAllTagsForUser(user))
    }

    @Test
    fun `Given a tag used by a pin, Then deleteAllTagsForUser removes it and its pin_tag junction row`() {
        // Given
        val user = createAndSaveUser()
        val tag = repository.saveTag(Tag(id = randomUUID(), author = user, name = "tag1", createdAt = storableNow()))
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

    // --- Names as identities ---

    private fun saveTag(name: String, user: User) =
        repository.saveTag(Tag(id = randomUUID(), author = user, name = name, createdAt = storableNow()))

    @Test
    fun `Given a tag owned by the user, Then findUserTagByName finds it whatever the ASCII case`() {
        // Given
        val user = createAndSaveUser()
        val tag = saveTag("landscape", user)

        // When
        val found = repository.findUserTagByName(user = user, name = "LandScape")

        // Then
        assertEquals(tag.id, found?.id)
    }

    @Test
    fun `Given a tag name already held up to ASCII case, Then saveTag is refused by the store`() {
        // Given: no translation, deliberately. TagCreator.findOrCreate reads through the same fold
        // first, so this violation is unreachable from the API and stays a PersistenceException.
        val user = createAndSaveUser()
        saveTag("landscape", user)

        // When / Then
        assertThrows<PersistenceException> { saveTag("Landscape", user) }
    }

    @Test
    fun `Given two authors, Then each may hold the same tag name`() {
        // Given
        val first = createAndSaveUser()
        val second = createAndSaveUser()
        saveTag("landscape", first)

        // When
        val tag = saveTag("landscape", second)

        // Then
        assertEquals("landscape", tag.name)
    }

    @Test
    fun `Given a lowercase accented tag name, Then its uppercase lookup misses`() {
        // Given: `collate nocase` folds A to Z and nothing else, so these stay two names. Ebean's
        // `ieq` lowercases the bind in Java, which is Unicode aware, and would have matched here.
        val user = createAndSaveUser()
        saveTag(LOWERCASE_ACCENTED_NAME, user)

        // When
        val found = repository.findUserTagByName(user = user, name = UPPERCASE_ACCENTED_NAME)

        // Then
        assertNull(found)
    }

    @Test
    fun `Given an uppercase accented tag name, Then its lowercase lookup misses`() {
        // Given: the other fold direction, where `ieq` and `collate nocase` already agree
        val user = createAndSaveUser()
        saveTag(UPPERCASE_ACCENTED_NAME, user)

        // When
        val found = repository.findUserTagByName(user = user, name = LOWERCASE_ACCENTED_NAME)

        // Then
        assertNull(found)
    }

    @Test
    fun `Given tag names differing only outside ASCII, Then both may be held by one author`() {
        // Given: the index folds exactly as the lookup does, so an ASCII-only fold keeps these apart
        val user = createAndSaveUser()
        saveTag(LOWERCASE_ACCENTED_NAME, user)

        // When
        val tag = saveTag(UPPERCASE_ACCENTED_NAME, user)

        // Then
        assertEquals(UPPERCASE_ACCENTED_NAME, tag.name)
    }

    // --- Creation timestamps ---

    @Test
    fun `Given a saved tag, Then reading it back exposes its creation timestamp`() {
        // Given
        val user = createAndSaveUser()
        repository.saveTag(Tag(id = randomUUID(), author = user, name = "landscape", createdAt = storableNow()))

        // When
        val found = repository.findUserTagByName(user = user, name = "landscape")

        // Then
        assertNotNull(found?.createdAt)
    }

    private companion object {
        // Precomposed U+00E9 / U+00C9 (not e + combining acute): the pair `collate nocase` leaves
        // alone and Java's `lowercase()` folds, which is the disagreement these cases pin.
        const val LOWERCASE_ACCENTED_NAME = "été"
        const val UPPERCASE_ACCENTED_NAME = "ÉTÉ"
    }
}
