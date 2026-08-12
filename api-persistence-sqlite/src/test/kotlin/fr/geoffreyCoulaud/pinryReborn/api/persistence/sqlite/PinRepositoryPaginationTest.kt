package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.CursorDirection
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.UUID.randomUUID

/**
 * How `PinRepository` pages: the cursor a page hands back, and the pivot a caller sends in.
 *
 * Pins created in the same clock tick share a `createdAt`. Without a tie-breaker the cursor
 * cannot advance past such a group, so a caller draining the pages loops forever on the same rows.
 * The draining tests use a hard page budget: an unfixed strategy blows the budget instead
 * of hanging the suite. The cursor-resolution tests below them pin what a pivot resolves to,
 * including one naming a pin that is not there.
 */
class PinRepositoryPaginationTest : PinRepositoryFixtures() {
    private val tiedPinCount = 5
    private val pageSize = 2
    private val maxPages = 10

    /**
     * `@WhenCreated` and the soft-deletion instant are written by the persistence layer, so the
     * shared instant is forced with raw SQL. Every pin in the (freshly truncated) database belongs
     * to the test at hand, so collapsing them all is safe.
     */
    private fun collapseAllPinInstants(column: String) {
        database
            .sqlUpdate("update pins set $column = (select min($column) from pins)")
            .execute()
    }

    private data class Drain(
        val seen: Set<UUID>,
        val pages: Int,
        val lastPageIds: List<UUID>,
        val backwardCursor: Cursor?,
    )

    /**
     * Walks pages forward until the cursor is exhausted or [maxPages] is reached, reporting the ids
     * it saw, how many pages it took, and where the last page leaves off.
     */
    private fun drainForward(readPage: (Cursor?) -> Page<Pin>): Drain {
        val seen = mutableSetOf<UUID>()
        var cursor: Cursor? = null
        var lastPageIds = emptyList<UUID>()
        var backwardCursor: Cursor? = null
        var pages = 0
        do {
            val page = readPage(cursor)
            lastPageIds = page.items.map { it.id }
            seen += lastPageIds
            cursor = page.nextCursor
            backwardCursor = page.previousCursor
            pages++
        } while (cursor != null && pages < maxPages)
        return Drain(seen, pages, lastPageIds, backwardCursor)
    }

    private fun drainActivePinsForward(
        user: User,
        sortStrategy: PinSortStrategy,
    ): Drain =
        drainForward { cursor ->
            repository.findPinsForUser(
                reader = user,
                cursor = cursor,
                pageSize = pageSize,
                sortStrategy = sortStrategy,
            )
        }

    @Test
    fun `Given more pins than a page sharing one creation instant, Then paging reaches them all`() {
        // Given
        val user = createAndSaveUser()
        val expectedIds = (1..tiedPinCount).map { createAndSavePin(user).id }.toSet()
        collapseAllPinInstants("when_created")

        // When
        val drain = drainActivePinsForward(user, PinSortStrategy.CREATED_AT_DESC)

        // Then
        assertTrue(drain.pages < maxPages, "pagination did not terminate")
        assertEquals(expectedIds, drain.seen)
    }

    @Test
    fun `Given ascending paging over pins sharing one creation instant, Then paging reaches them all`() {
        // Given
        val user = createAndSaveUser()
        val expectedIds = (1..tiedPinCount).map { createAndSavePin(user).id }.toSet()
        collapseAllPinInstants("when_created")

        // When
        val drain = drainActivePinsForward(user, PinSortStrategy.CREATED_AT_ASC)

        // Then
        assertTrue(drain.pages < maxPages, "pagination did not terminate")
        assertEquals(expectedIds, drain.seen)
    }

    @Test
    fun `Given pins sharing one creation instant, Then paging backward walks back to the start`() {
        // Given
        val user = createAndSaveUser()
        val expectedIds = (1..tiedPinCount).map { createAndSavePin(user).id }.toSet()
        collapseAllPinInstants("when_created")
        val forward = drainActivePinsForward(user, PinSortStrategy.CREATED_AT_DESC)

        // When
        val seen = forward.lastPageIds.toMutableSet()
        var cursor = forward.backwardCursor
        var pages = 0
        while (cursor != null && pages < maxPages) {
            val page =
                repository.findPinsForUser(
                    reader = user,
                    cursor = cursor,
                    pageSize = pageSize,
                    sortStrategy = PinSortStrategy.CREATED_AT_DESC,
                )
            seen += page.items.map { it.id }
            cursor = page.previousCursor
            pages++
        }

        // Then
        assertTrue(pages < maxPages, "backward pagination did not terminate")
        assertEquals(expectedIds, seen)
    }

    @Test
    fun `Given soft-deleted pins sharing one deletion instant, Then paging reaches them all`() {
        // Given
        val user = createAndSaveUser()
        val expectedIds =
            (1..tiedPinCount).map { repository.softDeletePin(createAndSavePin(user), storableNow()).id }.toSet()
        collapseAllPinInstants("soft_deleted_at")

        // When
        val drain =
            drainForward { cursor ->
                repository.findSoftDeletedPinsForUser(
                    reader = user,
                    cursor = cursor,
                    pageSize = pageSize,
                    sortStrategy = PinSortStrategy.DELETED_AT_DESC,
                )
            }

        // Then
        assertTrue(drain.pages < maxPages, "pagination did not terminate")
        assertEquals(expectedIds, drain.seen)
    }

    // --- Pagination cursor resolution ---

    @Test
    fun `Given a cursor pointing to an existing pin, Then findPinsForUser resumes from it`() {
        // Given
        val user = createAndSaveUser()
        val firstPin = createAndSavePin(user, createdAt = firstInstant)
        val secondPin = createAndSavePin(user, createdAt = secondInstant)
        val cursor = Cursor(pivotId = firstPin.id, direction = CursorDirection.FORWARD)

        // When
        val page =
            repository.findPinsForUser(
                reader = user,
                cursor = cursor,
                pageSize = 10,
                sortStrategy = PinSortStrategy.CREATED_AT_ASC,
            )

        // Then
        assertTrue(page.items.none { it.id == firstPin.id })
        assertNotNull(page.items.find { it.id == secondPin.id })
    }

    @Test
    fun `Given a cursor pointing to a nonexistent pin, Then findPinsForUser treats it as the first page`() {
        // Given
        val user = createAndSaveUser()
        val pin = createAndSavePin(user)
        val cursor = Cursor(pivotId = randomUUID(), direction = CursorDirection.FORWARD)

        // When
        val page =
            repository.findPinsForUser(
                reader = user,
                cursor = cursor,
                pageSize = 10,
                sortStrategy = PinSortStrategy.CREATED_AT_ASC,
            )

        // Then
        assertEquals(1, page.items.size)
        assertEquals(pin.id, page.items[0].id)
    }

    @Test
    fun `Given a cursor pointing to an existing soft-deleted pin, Then findSoftDeletedPinsForUser resumes from it`() {
        // Given
        val user = createAndSaveUser()
        val firstPin = repository.softDeletePin(createAndSavePin(user, createdAt = firstInstant), storableNow())
        val secondPin = repository.softDeletePin(createAndSavePin(user, createdAt = secondInstant), storableNow())
        val cursor = Cursor(pivotId = firstPin.id, direction = CursorDirection.FORWARD)

        // When
        val page =
            repository.findSoftDeletedPinsForUser(
                reader = user,
                cursor = cursor,
                pageSize = 10,
                sortStrategy = PinSortStrategy.CREATED_AT_ASC,
            )

        // Then
        assertTrue(page.items.none { it.id == firstPin.id })
        assertNotNull(page.items.find { it.id == secondPin.id })
    }

    @Test
    fun `Given a cursor pointing to a nonexistent pin, Then findSoftDeletedPinsForUser treats it as the first page`() {
        // Given
        val user = createAndSaveUser()
        val pin = repository.softDeletePin(createAndSavePin(user), storableNow())
        val cursor = Cursor(pivotId = randomUUID(), direction = CursorDirection.FORWARD)

        // When
        val page =
            repository.findSoftDeletedPinsForUser(
                reader = user,
                cursor = cursor,
                pageSize = 10,
                sortStrategy = PinSortStrategy.CREATED_AT_ASC,
            )

        // Then
        assertEquals(1, page.items.size)
        assertEquals(pin.id, page.items[0].id)
    }

    @Test
    fun `Given many pins, Then findPinsForUser exposes both cursors`() {
        // Given
        val user = createAndSaveUser()
        repeat(3) { createAndSavePin(user) }

        // When
        val page =
            repository.findPinsForUser(
                reader = user,
                cursor = null,
                pageSize = 2,
                sortStrategy = PinSortStrategy.CREATED_AT_ASC,
            )

        // Then
        assertEquals(2, page.items.size)
        assertNotNull(page.nextCursor)
        assertNotNull(page.previousCursor)
    }

    @Test
    fun `Given many soft-deleted pins, Then findSoftDeletedPinsForUser exposes both cursors`() {
        // Given
        val user = createAndSaveUser()
        repeat(3) { repository.softDeletePin(createAndSavePin(user), storableNow()) }

        // When
        val page =
            repository.findSoftDeletedPinsForUser(
                reader = user,
                cursor = null,
                pageSize = 2,
                sortStrategy = PinSortStrategy.CREATED_AT_ASC,
            )

        // Then
        assertEquals(2, page.items.size)
        assertNotNull(page.nextCursor)
        assertNotNull(page.previousCursor)
    }

    @Test
    fun `Given no soft-deleted pins, Then findSoftDeletedPinsForUser returns an empty page with no cursors`() {
        // Given
        val user = createAndSaveUser()
        createAndSavePin(user)

        // When
        val page =
            repository.findSoftDeletedPinsForUser(
                reader = user,
                cursor = null,
                pageSize = 10,
                sortStrategy = PinSortStrategy.CREATED_AT_ASC,
            )

        // Then
        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
        assertNull(page.previousCursor)
    }
}
