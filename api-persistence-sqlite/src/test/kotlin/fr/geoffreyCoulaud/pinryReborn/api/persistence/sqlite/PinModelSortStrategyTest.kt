package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.PinModelSortStrategy
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class PinModelSortStrategyTest {
    @Test
    fun `Given CREATED_AT_ASC, Then fromDomain returns CreatedAtAsc`() {
        // Given
        val strategy = PinSortStrategy.CREATED_AT_ASC

        // When
        val result = PinModelSortStrategy.fromDomain(strategy)

        // Then
        assertInstanceOf(PinModelSortStrategy.CreatedAtAsc::class.java, result)
    }

    @Test
    fun `Given CREATED_AT_DESC, Then fromDomain returns CreatedAtDesc`() {
        // Given
        val strategy = PinSortStrategy.CREATED_AT_DESC

        // When
        val result = PinModelSortStrategy.fromDomain(strategy)

        // Then
        assertInstanceOf(PinModelSortStrategy.CreatedAtDesc::class.java, result)
    }

    @Test
    fun `Given DELETED_AT_DESC, Then fromDomain returns DeletedAtDesc`() {
        // Given
        val strategy = PinSortStrategy.DELETED_AT_DESC

        // When
        val result = PinModelSortStrategy.fromDomain(strategy)

        // Then
        assertInstanceOf(PinModelSortStrategy.DeletedAtDesc::class.java, result)
    }
}
