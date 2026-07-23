package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.http.RangeNotSatisfiableException
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.core.UriInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RangeNotSatisfiableExceptionMapperTest {
    private val mapper = RangeNotSatisfiableExceptionMapper().apply {
        uriInfo = mockk<UriInfo> { every { path } returns "/api/v1/me/exports/x/download" }
    }

    @Test
    fun `Given an unsatisfiable range, Then it maps to 416 with a Content-Range total`() {
        // Given
        val exception = RangeNotSatisfiableException(totalSize = 4096)

        // When
        val response = mapper.toResponse(exception)

        // Then
        assertEquals(416, response.status)
        assertEquals("bytes */4096", response.getHeaderString("Content-Range"))
    }
}
