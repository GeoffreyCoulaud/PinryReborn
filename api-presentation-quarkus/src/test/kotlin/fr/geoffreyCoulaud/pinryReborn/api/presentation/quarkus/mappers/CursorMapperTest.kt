package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.CursorDirection
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.common.CursorDirectionDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.CursorMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.CursorMapper.toDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CursorMapperTest {
    @Test
    fun `Given BACKWARD direction dto, Then toDomain maps to BACKWARD`() {
        // Given, When
        val result = CursorDirectionDto.BACKWARD.toDomain()

        // Then
        assertEquals(CursorDirection.BACKWARD, result)
    }

    @Test
    fun `Given FORWARD direction dto, Then toDomain maps to FORWARD`() {
        // Given, When
        val result = CursorDirectionDto.FORWARD.toDomain()

        // Then
        assertEquals(CursorDirection.FORWARD, result)
    }

    @Test
    fun `Given FORWARD direction, Then toDto maps to FORWARD dto`() {
        // Given, When
        val result = CursorDirection.FORWARD.toDto()

        // Then
        assertEquals(CursorDirectionDto.FORWARD, result)
    }

    @Test
    fun `Given BACKWARD direction, Then toDto maps to BACKWARD dto`() {
        // Given, When
        val result = CursorDirection.BACKWARD.toDto()

        // Then
        assertEquals(CursorDirectionDto.BACKWARD, result)
    }
}
