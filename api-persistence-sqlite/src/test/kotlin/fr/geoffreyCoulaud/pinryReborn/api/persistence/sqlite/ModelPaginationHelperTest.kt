package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.CursorDirection
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.PinModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.PinModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QPinModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.ModelCursor
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.ModelPaginationHelper
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.PinModelSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserRepository
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

/**
 * Exercises [ModelPaginationHelper] directly (bypassing PinRepository) so every
 * direction / has-more / cursor-presence combination can be driven precisely.
 */
class ModelPaginationHelperTest : RepositoryTest() {
    private val userRepository = UserRepository(database)
    private val helper = ModelPaginationHelper<PinModel, QPinModel>()
    private val strategy = PinModelSortStrategy.CreatedAtAsc()

    private fun createUser(): User = userRepository.saveUser(User(id = randomUUID(), name = createRandomString()))

    private fun baseQueryFor(user: User) = QPinModel().author.id.equalTo(user.id)

    private fun pageFor(
        user: User,
        cursor: ModelCursor<PinModel>?,
        pageSize: Int,
    ) = helper.getPage(
        cursor = cursor,
        pageSize = pageSize,
        baseQuery = baseQueryFor(user),
        sortStrategy = strategy,
    )

    /**
     * Persist [count] pins for [user] with strictly increasing `whenCreated` timestamps
     * (index 0 is the oldest). Ebean's `@WhenCreated` only applies on insert, so the manually
     * assigned timestamp is set via a follow-up update to guarantee deterministic ordering
     * (two inserts within the same test can otherwise land on the same millisecond).
     */
    private fun seedPins(
        user: User,
        count: Int,
    ): List<PinModel> {
        val base = Instant.now()
        return (0 until count).map { index ->
            val pin =
                Pin(
                    id = randomUUID(),
                    author = user,
                    sourceContextUrl = "https://example.com/$index",
                    sourceMediaUrl = "https://example.com/image-$index.jpeg",
                    description = "Pin $index",
                    tags = emptyList(),
                )
            val model = pin.toModel()
            database.save(model)
            model.whenCreated = base.plusSeconds(index.toLong())
            database.save(model)
            model
        }
    }

    // --- No cursor ---

    @Test
    fun `Given no cursor and no pins, Then the page is empty with no cursors`() {
        // Given
        val user = createUser()

        // When
        val page = pageFor(user, null, 5)

        // Then
        assertTrue(page.items.isEmpty())
        assertNull(page.previousCursor)
        assertNull(page.nextCursor)
    }

    @Test
    fun `Given no cursor and fewer pins than the page size, Then hasMore is false`() {
        // Given
        val user = createUser()
        seedPins(user, count = 2)

        // When
        val page = pageFor(user, null, 5)

        // Then
        assertEquals(2, page.items.size)
        assertNull(page.nextCursor)
        assertNotNull(page.previousCursor)
    }

    @Test
    fun `Given no cursor and more pins than the page size, Then hasMore is true`() {
        // Given
        val user = createUser()
        val pins = seedPins(user, count = 3)

        // When
        val page = pageFor(user, null, 2)

        // Then
        assertEquals(listOf(pins[0].id, pins[1].id), page.items.map { it.id })
        assertNotNull(page.nextCursor)
        assertEquals(pins[1].id, page.nextCursor!!.pivot.id)
    }

    // --- Forward cursor ---

    @Test
    fun `Given a forward cursor with more items after, Then hasMore is true`() {
        // Given
        val user = createUser()
        val pins = seedPins(user, count = 5)
        val cursor = ModelCursor(pivot = pins[0], direction = CursorDirection.FORWARD)

        // When
        val page = pageFor(user, cursor, 2)

        // Then
        assertEquals(listOf(pins[1].id, pins[2].id), page.items.map { it.id })
        assertNotNull(page.nextCursor)
    }

    @Test
    fun `Given a forward cursor with no more items after, Then hasMore is false`() {
        // Given
        val user = createUser()
        val pins = seedPins(user, count = 3)
        val cursor = ModelCursor(pivot = pins[0], direction = CursorDirection.FORWARD)

        // When
        val page = pageFor(user, cursor, 5)

        // Then
        assertEquals(listOf(pins[1].id, pins[2].id), page.items.map { it.id })
        assertNull(page.nextCursor)
    }

    // --- Backward cursor ---

    @Test
    fun `Given a backward cursor with more items before, Then hasMore is true`() {
        // Given
        val user = createUser()
        val pins = seedPins(user, count = 5)
        val cursor = ModelCursor(pivot = pins[4], direction = CursorDirection.BACKWARD)

        // When
        val page = pageFor(user, cursor, 2)

        // Then
        assertEquals(listOf(pins[2].id, pins[3].id), page.items.map { it.id })
        assertNotNull(page.previousCursor)
    }

    @Test
    fun `Given a backward cursor with no more items before, Then hasMore is false`() {
        // Given
        val user = createUser()
        val pins = seedPins(user, count = 3)
        val cursor = ModelCursor(pivot = pins[2], direction = CursorDirection.BACKWARD)

        // When
        val page = pageFor(user, cursor, 5)

        // Then
        assertEquals(listOf(pins[0].id, pins[1].id), page.items.map { it.id })
        assertNull(page.previousCursor)
        assertNotNull(page.nextCursor)
        assertEquals(pins[0].id, page.nextCursor!!.pivot.id)
    }

    @Test
    fun `Given a backward cursor matching no rows, Then the page is empty with no cursors`() {
        // Given
        val user = createUser()
        val otherUsersPin = seedPins(createUser(), count = 1).first()
        val cursor = ModelCursor(pivot = otherUsersPin, direction = CursorDirection.BACKWARD)

        // When
        val page = pageFor(user, cursor, 5)

        // Then
        assertTrue(page.items.isEmpty())
        assertNull(page.previousCursor)
        assertNull(page.nextCursor)
    }
}
