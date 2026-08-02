package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SearchEmptyQueryError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID.randomUUID

class TagSearcherTest {
    private val tagRepository = mockk<TagRepositoryInterface>()
    private val useCase = TagSearcher(tagRepository = tagRepository)

    private fun createUser() = User(id = randomUUID(), name = "John Doe", createdAt = TestTime.now)

    private fun createTag(user: User, name: String) = Tag(id = randomUUID(), author = user, name = name,
        createdAt = TestTime.now)

    @Test
    fun `Given empty query, Then throws SearchEmptyQueryError`() {
        // Given
        val user = createUser()

        // When, Then
        assertThrows<SearchEmptyQueryError> {
            useCase.searchTags(user = user, query = "", limit = 10)
        }
    }

    @Test
    fun `Given blank query, Then throws SearchEmptyQueryError`() {
        // Given
        val user = createUser()

        // When, Then
        assertThrows<SearchEmptyQueryError> {
            useCase.searchTags(user = user, query = "   ", limit = 10)
        }
    }

    @Test
    fun `Given no tags, Then returns empty list`() {
        // Given
        val user = createUser()
        every { tagRepository.findAllTagsForUser(user) } returns emptyList()

        // When
        val results = useCase.searchTags(user = user, query = "test", limit = 10)

        // Then
        assertTrue(results.isEmpty())
    }

    @Test
    fun `Given exact match, Then returns tag with score 1`() {
        // Given
        val user = createUser()
        val tag = createTag(user, "landscape")
        every { tagRepository.findAllTagsForUser(user) } returns listOf(tag)

        // When
        val results = useCase.searchTags(user = user, query = "landscape", limit = 10)

        // Then
        assertEquals(1, results.size)
        assertEquals("landscape", results[0].item.name)
        assertEquals(1.0, results[0].score, 0.001)
    }

    @Test
    fun `Given multiple tags, Then returns results sorted by score descending`() {
        // Given
        val user = createUser()
        val tags = listOf(
            createTag(user, "nature"),
            createTag(user, "landscape"),
            createTag(user, "landscaping")
        )
        every { tagRepository.findAllTagsForUser(user) } returns tags

        // When
        val results = useCase.searchTags(user = user, query = "landscape", limit = 10)

        // Then
        assertTrue(results.size > 1)
        assertEquals("landscape", results[0].item.name)
        // Results should be sorted by descending score
        for (i in 0 until results.size - 1) {
            assertTrue(results[i].score >= results[i + 1].score)
        }
    }

    @Test
    fun `Given limit parameter, Then returns at most limit results`() {
        // Given
        val user = createUser()
        val tags = listOf(
            createTag(user, "test1"),
            createTag(user, "test2"),
            createTag(user, "test3"),
            createTag(user, "test4"),
            createTag(user, "test5")
        )
        every { tagRepository.findAllTagsForUser(user) } returns tags

        // When
        val results = useCase.searchTags(user = user, query = "test", limit = 2)

        // Then
        assertEquals(2, results.size)
    }

    @Test
    fun `Given low score results, Then filters them out`() {
        // Given
        val user = createUser()
        val tags = listOf(
            createTag(user, "landscape"),
            createTag(user, "xyz")
        )
        every { tagRepository.findAllTagsForUser(user) } returns tags

        // When
        val results = useCase.searchTags(user = user, query = "landscape", limit = 10)

        // Then
        assertEquals(1, results.size)
        assertEquals("landscape", results[0].item.name)
    }

    @Test
    fun `Given typo in query, Then still finds matching tag`() {
        // Given
        val user = createUser()
        val tag = createTag(user, "landscape")
        every { tagRepository.findAllTagsForUser(user) } returns listOf(tag)

        // When
        val results = useCase.searchTags(user = user, query = "landscpe", limit = 10)

        // Then
        assertEquals(1, results.size)
        assertEquals("landscape", results[0].item.name)
    }

    @Test
    fun `Given case-insensitive query, Then matches tags regardless of case`() {
        // Given
        val user = createUser()
        val tag = createTag(user, "Landscape")
        every { tagRepository.findAllTagsForUser(user) } returns listOf(tag)

        // When
        val results = useCase.searchTags(user = user, query = "landscape", limit = 10)

        // Then
        assertEquals(1, results.size)
        assertEquals("Landscape", results[0].item.name)
    }
}
