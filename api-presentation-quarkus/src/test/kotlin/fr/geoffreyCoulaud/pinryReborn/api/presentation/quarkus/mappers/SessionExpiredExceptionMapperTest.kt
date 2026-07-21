package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ProblemDetail
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.SessionExpiredException
import jakarta.ws.rs.core.UriInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SessionExpiredExceptionMapperTest {
    @Test
    fun `Given an expired session, Then it maps to 401 SESSION_EXPIRED with a Bearer challenge`() {
        val mapper = SessionExpiredExceptionMapper()
        mapper.uriInfo = mockk<UriInfo> { every { path } returns "/api/v1/me" }

        val response = mapper.toResponse(SessionExpiredException("Session token expired"))

        assertEquals(401, response.status)
        assertEquals("Bearer", response.getHeaderString("WWW-Authenticate"))
        assertEquals("SESSION_EXPIRED", (response.entity as ProblemDetail).code)
    }
}
