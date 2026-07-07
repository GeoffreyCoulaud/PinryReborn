package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input.PinSortStrategyInputEnum
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.PinSortStrategyMapper.toDomain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PinSortStrategyMapperTest {
    @Test
    fun `Given CREATED_AT_ASC, Then toDomain maps to CREATED_AT_ASC`() {
        // Given, When
        val result = PinSortStrategyInputEnum.CREATED_AT_ASC.toDomain()

        // Then
        assertEquals(PinSortStrategy.CREATED_AT_ASC, result)
    }

    @Test
    fun `Given CREATED_AT_DESC, Then toDomain maps to CREATED_AT_DESC`() {
        // Given, When
        val result = PinSortStrategyInputEnum.CREATED_AT_DESC.toDomain()

        // Then
        assertEquals(PinSortStrategy.CREATED_AT_DESC, result)
    }
}
