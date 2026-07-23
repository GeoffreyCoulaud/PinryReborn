package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ProblemDetail
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ExportTooSoonError
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.core.UriInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExportTooSoonExceptionMapperTest {
    private val mapper = ExportTooSoonExceptionMapper().apply {
        uriInfo = mockk<UriInfo> { every { path } returns "/api/v1/me/exports" }
    }

    @Test
    fun `Given an export requested too soon, Then it maps to 429 with a numeric Retry-After`() {
        // Given
        val exception = ExportTooSoonError(retryAfterSeconds = 42)

        // When
        val response = mapper.toResponse(exception)

        // Then
        assertEquals(429, response.status)
        assertEquals("42", response.getHeaderString("Retry-After"))
        assertEquals("EXPORT_TOO_SOON", (response.entity as ProblemDetail).code)
    }
}
