package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/**
 * The recycle bin half of `PinRepository`: soft deletion, restoration, the reads that include or
 * exclude recycled pins, and permanent deletion. Split from `PinRepositoryTest` to keep it under
 * detekt's `LargeClass` threshold (mirrors `PinRepositoryPaginationTest`'s precedent).
 */
class PinRepositorySoftDeleteTest : PinRepositoryFixtures() {
    @Test
    fun `Given soft-deleted pin, Then findPinsForUser excludes it`() {
        // Given
        val user = createAndSaveUser()
        val pin = createAndSavePin(user)
        repository.softDeletePin(pin, storableNow())

        // When
        val page = repository.findPinsForUser(
            reader = user,
            cursor = null,
            pageSize = 10,
            sortStrategy = PinSortStrategy.CREATED_AT_ASC,
        )

        // Then
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `Given soft-deleted pin, Then findAllPinsForUser excludes it`() {
        // Given
        val user = createAndSaveUser()
        val pin = createAndSavePin(user)
        repository.softDeletePin(pin, storableNow())

        // When
        val pins = repository.findAllPinsForUser(user)

        // Then
        assertTrue(pins.isEmpty())
    }

    @Test
    fun `Given soft-deleted pin, Then findAllSoftDeletedPinsForUser includes it`() {
        // Given
        val user = createAndSaveUser()
        createAndSavePin(user)
        val softDeletedPin = createAndSavePin(user)
        repository.softDeletePin(softDeletedPin, storableNow())

        // When
        val pins = repository.findAllSoftDeletedPinsForUser(user)

        // Then
        assertEquals(1, pins.size)
        assertEquals(softDeletedPin.id, pins[0].id)
    }

    @Test
    fun `Given no soft-deleted pins, Then findAllSoftDeletedPinsForUser returns an empty list`() {
        // Given
        val user = createAndSaveUser()
        createAndSavePin(user)

        // When
        val pins = repository.findAllSoftDeletedPinsForUser(user)

        // Then
        assertTrue(pins.isEmpty())
    }

    @Test
    fun `Given another user's soft-deleted pin, Then findAllSoftDeletedPinsForUser excludes it`() {
        // Given
        val userA = createAndSaveUser()
        val userB = createAndSaveUser()
        val userASoftDeletedPin = createAndSavePin(userA)
        repository.softDeletePin(userASoftDeletedPin, storableNow())
        val userBSoftDeletedPin = createAndSavePin(userB)
        repository.softDeletePin(userBSoftDeletedPin, storableNow())

        // When
        val pins = repository.findAllSoftDeletedPinsForUser(userA)

        // Then
        assertEquals(1, pins.size)
        assertEquals(userASoftDeletedPin.id, pins[0].id)
    }

    @Test
    fun `Given soft-deleted pin, Then findSoftDeletedPinsForUser includes it`() {
        // Given
        val user = createAndSaveUser()
        val pin = createAndSavePin(user)
        repository.softDeletePin(pin, storableNow())

        // When
        val page = repository.findSoftDeletedPinsForUser(
            reader = user,
            cursor = null,
            pageSize = 10,
            sortStrategy = PinSortStrategy.CREATED_AT_ASC,
        )

        // Then
        assertEquals(1, page.items.size)
        assertEquals(pin.id, page.items[0].id)
    }

    @Test
    fun `Given pin, Then softDeletePin sets softDeletedAt`() {
        // Given
        val user = createAndSaveUser()
        val pin = createAndSavePin(user)

        // When
        val result = repository.softDeletePin(pin, storableNow())

        // Then
        assertNotNull(result.softDeletedAt)
    }

    @Test
    fun `Given a deletion instant, Then softDeletePin stores it as both softDeletedAt and updatedAt`() {
        // Given
        val user = createAndSaveUser()
        val pin = createAndSavePin(user)
        val deletionInstant = Instant.parse("2026-01-02T03:04:05Z")

        // When
        repository.softDeletePin(pin, deletionInstant)

        // Then - the instant reaches the columns unchanged, read back from the store
        val stored = requireNotNull(repository.findPinById(pin.id))
        assertEquals(deletionInstant, stored.softDeletedAt)
        assertEquals(deletionInstant, stored.updatedAt)
    }

    @Test
    fun `Given soft-deleted pin, Then restorePin clears softDeletedAt`() {
        // Given
        val user = createAndSaveUser()
        val pin = createAndSavePin(user)
        val softDeleted = repository.softDeletePin(pin, storableNow())

        // When
        val result = repository.restorePin(softDeleted, storableNow())

        // Then
        assertNull(result.softDeletedAt)
    }

    @Test
    fun `Given a restoration instant, Then restorePin stores it as updatedAt and clears softDeletedAt`() {
        // Given
        val user = createAndSaveUser()
        val pin = createAndSavePin(user)
        val softDeleted = repository.softDeletePin(pin, storableNow())
        val restorationInstant = Instant.parse("2026-02-03T04:05:06Z")

        // When
        repository.restorePin(softDeleted, restorationInstant)

        // Then - the instant reaches the column unchanged, read back from the store
        val stored = requireNotNull(repository.findPinById(pin.id))
        assertEquals(restorationInstant, stored.updatedAt)
        assertNull(stored.softDeletedAt)
    }

    @Test
    fun `Given a pin absent from the store, Then softDeletePin throws IllegalStateException naming its id`() {
        // Given - the use case read and validated a pin a concurrent hard delete has since removed;
        // absence at the transition is an illegal state, not a missing argument
        val absentPin = createPin()

        // When / Then
        val exception = assertThrows<IllegalStateException> {
            repository.softDeletePin(absentPin, storableNow())
        }
        assertTrue(exception.message!!.contains(absentPin.id.toString()))
    }

    @Test
    fun `Given a pin absent from the store, Then restorePin throws IllegalStateException naming its id`() {
        // Given - same illegal-state condition as softDeletePin on an absent row
        val absentPin = createPin()

        // When / Then
        val exception = assertThrows<IllegalStateException> {
            repository.restorePin(absentPin, storableNow())
        }
        assertTrue(exception.message!!.contains(absentPin.id.toString()))
    }

    @Test
    fun `Given soft-deleted pin, Then permanentlyDeletePin removes it and its tag associations`() {
        // Given
        val user = createAndSaveUser()
        val tag = createAndSaveTag(name = "tag1", user = user)
        val pin = createPinWithTags(tag).copy(author = user)
        repository.savePin(pin)
        val softDeleted = repository.softDeletePin(pin, storableNow())

        // When
        repository.permanentlyDeletePin(softDeleted)

        // Then
        assertNull(repository.findPinById(pin.id))
    }

    @Test
    fun `Given soft-deleted pin, Then permanentlyDeletePin removes it and its board associations`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard(name = "board1", user = user)
        val pin = createPinWithBoards(board).copy(author = user)
        repository.savePin(pin)
        val softDeleted = repository.softDeletePin(pin, storableNow())

        // When
        // If the pin_board_model row were not deleted first, this would fail with a foreign
        // key constraint violation (pin_board_model.pin_id references pins on delete restrict).
        repository.permanentlyDeletePin(softDeleted)

        // Then
        assertNull(repository.findPinById(pin.id))
    }

    @Test
    fun `Given multiple soft-deleted pins, Then permanentlyDeleteAllSoftDeletedPinsForUser removes all`() {
        // Given
        val user = createAndSaveUser()
        val pin1 = createAndSavePin(user)
        val pin2 = createAndSavePin(user)
        val activePin = createAndSavePin(user)
        repository.softDeletePin(pin1, storableNow())
        repository.softDeletePin(pin2, storableNow())

        // When
        repository.permanentlyDeleteAllSoftDeletedPinsForUser(user)

        // Then
        assertNull(repository.findPinById(pin1.id))
        assertNull(repository.findPinById(pin2.id))
        assertNotNull(repository.findPinById(activePin.id))
    }

    @Test
    fun `Given multiple soft-deleted pins with boards, Then permanentlyDeleteAllSoftDeletedPinsForUser removes them`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard(name = "board1", user = user)
        val pin1 = repository.savePin(createPinWithBoards(board).copy(author = user))
        val pin2 = repository.savePin(createPinWithBoards(board).copy(author = user))
        repository.softDeletePin(pin1, storableNow())
        repository.softDeletePin(pin2, storableNow())

        // When
        // If the pin_board_model rows were not deleted first, this would fail with a foreign
        // key constraint violation (pin_board_model.pin_id references pins on delete restrict).
        repository.permanentlyDeleteAllSoftDeletedPinsForUser(user)

        // Then
        assertNull(repository.findPinById(pin1.id))
        assertNull(repository.findPinById(pin2.id))
    }

    @Test
    fun `Given no soft-deleted pins, Then permanentlyDeleteAllSoftDeletedPinsForUser is a no-op`() {
        // Given
        val user = createAndSaveUser()
        val activePin = createAndSavePin(user)

        // When
        repository.permanentlyDeleteAllSoftDeletedPinsForUser(user)

        // Then
        assertNotNull(repository.findPinById(activePin.id))
    }

    @Test
    fun `Given active and soft-deleted pins, Then permanentlyDeleteAllPinsForUser removes all`() {
        // Given
        val user = createAndSaveUser()
        val tag = createAndSaveTag(name = "tag1", user = user)
        val board = createAndSaveBoard(name = "board1", user = user)
        val activePin = repository.savePin(createPinWithTags(tag).copy(author = user, boards = listOf(board)))
        val toSoftDelete = createAndSavePin(user)
        repository.softDeletePin(toSoftDelete, storableNow())

        // When
        // If the pin_tag_model / pin_board_model rows were not deleted first, this would fail
        // with a foreign key constraint violation (references pins on delete restrict).
        repository.permanentlyDeleteAllPinsForUser(user)

        // Then
        assertEquals(emptyList<Pin>(), repository.findAllPinsForUser(user))
        assertEquals(emptyList<Pin>(), repository.findAllSoftDeletedPinsForUser(user))
        assertNull(repository.findPinById(activePin.id))
        assertNull(repository.findPinById(toSoftDelete.id))
    }

    @Test
    fun `Given no pins for the user, Then permanentlyDeleteAllPinsForUser is a no-op`() {
        // Given
        val user = createAndSaveUser()

        // When
        repository.permanentlyDeleteAllPinsForUser(user)

        // Then
        assertEquals(emptyList<Pin>(), repository.findAllPinsForUser(user))
        assertEquals(emptyList<Pin>(), repository.findAllSoftDeletedPinsForUser(user))
    }

    @Test
    fun `Given active and soft-deleted pins, Then findAllPinIdsForUser returns all their ids`() {
        // Given
        val user = createAndSaveUser()
        val activePin = createAndSavePin(user)
        val softDeletedPin = repository.softDeletePin(createAndSavePin(user), storableNow())

        // When
        val pinIds = repository.findAllPinIdsForUser(user)

        // Then
        assertEquals(setOf(activePin.id, softDeletedPin.id), pinIds.toSet())
    }
}
