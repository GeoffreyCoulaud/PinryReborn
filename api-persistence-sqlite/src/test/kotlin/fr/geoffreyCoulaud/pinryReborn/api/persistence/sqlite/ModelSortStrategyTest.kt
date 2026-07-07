package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.CursorDirection
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.PinModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QPinModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.ModelCursor
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.ModelSortStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

/**
 * [ModelSortStrategy] routing logic (`filterCursorAndNeighbors`, `sortCursorNeighbors`) is
 * exercised through a minimal test double that just records which abstract member got called,
 * instead of a real [fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.PinModelSortStrategy],
 * to keep the assertions focused on the routing itself.
 */
class ModelSortStrategyTest : RepositoryTest() {
    private class RecordingSortStrategy : ModelSortStrategy<PinModel, QPinModel>() {
        var forwardFilterCalled = false
        var backwardFilterCalled = false
        var forwardSortCalled = false
        var backwardSortCalled = false

        override fun filterCursorAndForwardNeighbors(
            cursor: ModelCursor<PinModel>,
            query: QPinModel,
        ): QPinModel {
            forwardFilterCalled = true
            return query
        }

        override fun filterCursorAndBackwardNeighbors(
            cursor: ModelCursor<PinModel>,
            query: QPinModel,
        ): QPinModel {
            backwardFilterCalled = true
            return query
        }

        override fun sortCursorAndForwardNeighbors(query: QPinModel): QPinModel {
            forwardSortCalled = true
            return query
        }

        override fun sortCursorAndBackwardNeighbors(query: QPinModel): QPinModel {
            backwardSortCalled = true
            return query
        }
    }

    private fun createPivot(): PinModel =
        PinModel(
            id = randomUUID(),
            author = UserModel(id = randomUUID(), name = "author"),
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/image.jpeg",
            description = "d",
        )

    // --- filterCursorAndNeighbors ---

    @Test
    fun `Given no cursor, Then filterCursorAndNeighbors returns the query unchanged`() {
        // Given
        val strategy = RecordingSortStrategy()
        val query = QPinModel()

        // When
        val result = strategy.filterCursorAndNeighbors(query = query, cursor = null)

        // Then
        assertSame(query, result)
        assertFalse(strategy.forwardFilterCalled)
        assertFalse(strategy.backwardFilterCalled)
    }

    @Test
    fun `Given a forward cursor, Then filterCursorAndNeighbors routes to the forward filter`() {
        // Given
        val strategy = RecordingSortStrategy()
        val cursor = ModelCursor(pivot = createPivot(), direction = CursorDirection.FORWARD)

        // When
        strategy.filterCursorAndNeighbors(query = QPinModel(), cursor = cursor)

        // Then
        assertTrue(strategy.forwardFilterCalled)
        assertFalse(strategy.backwardFilterCalled)
    }

    @Test
    fun `Given a backward cursor, Then filterCursorAndNeighbors routes to the backward filter`() {
        // Given
        val strategy = RecordingSortStrategy()
        val cursor = ModelCursor(pivot = createPivot(), direction = CursorDirection.BACKWARD)

        // When
        strategy.filterCursorAndNeighbors(query = QPinModel(), cursor = cursor)

        // Then
        assertTrue(strategy.backwardFilterCalled)
        assertFalse(strategy.forwardFilterCalled)
    }

    // --- sortCursorNeighbors ---

    @Test
    fun `Given no cursor, Then sortCursorNeighbors defaults to the forward sort`() {
        // Given
        val strategy = RecordingSortStrategy()

        // When
        strategy.sortCursorNeighbors(query = QPinModel(), cursor = null)

        // Then
        assertTrue(strategy.forwardSortCalled)
        assertFalse(strategy.backwardSortCalled)
    }

    @Test
    fun `Given a forward cursor, Then sortCursorNeighbors routes to the forward sort`() {
        // Given
        val strategy = RecordingSortStrategy()
        val cursor = ModelCursor(pivot = createPivot(), direction = CursorDirection.FORWARD)

        // When
        strategy.sortCursorNeighbors(query = QPinModel(), cursor = cursor)

        // Then
        assertTrue(strategy.forwardSortCalled)
        assertFalse(strategy.backwardSortCalled)
    }

    @Test
    fun `Given a backward cursor, Then sortCursorNeighbors routes to the backward sort`() {
        // Given
        val strategy = RecordingSortStrategy()
        val cursor = ModelCursor(pivot = createPivot(), direction = CursorDirection.BACKWARD)

        // When
        strategy.sortCursorNeighbors(query = QPinModel(), cursor = cursor)

        // Then
        assertTrue(strategy.backwardSortCalled)
        assertFalse(strategy.forwardSortCalled)
    }
}
