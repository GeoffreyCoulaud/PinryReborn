package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.TagRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserRepository
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class TagRepositoryTest : RepositoryTest() {
    private val repository = TagRepository(database)
    private val userRepository = UserRepository(database)

    private fun createAndSaveUser(): User =
        userRepository.saveUser(
            User(id = randomUUID(), name = createRandomString()),
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
}
