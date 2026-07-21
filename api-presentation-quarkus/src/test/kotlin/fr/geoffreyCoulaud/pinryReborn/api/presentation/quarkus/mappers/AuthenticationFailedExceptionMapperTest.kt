package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ProblemDetail
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenExpiredError
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.AuthenticationFailedException
import jakarta.ws.rs.core.UriInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AuthenticationFailedExceptionMapperTest {
    private val mapper = AuthenticationFailedExceptionMapper().apply {
        uriInfo = mockk<UriInfo> { every { path } returns "/api/v1/me" }
    }

    @Test
    fun `Given a session token expired cause, Then it maps to 401 SESSION_EXPIRED with a Bearer challenge`() {
        val exception = AuthenticationFailedException("Session token expired", SessionTokenExpiredError())

        val response = mapper.toResponse(exception)

        assertEquals(401, response.status)
        assertEquals("Bearer", response.getHeaderString("WWW-Authenticate"))
        assertEquals("SESSION_EXPIRED", (response.entity as ProblemDetail).code)
    }

    @Test
    fun `Given a non-expired cause, Then it maps to 401 AUTHENTICATION_FAILED with a Bearer challenge`() {
        val exception = AuthenticationFailedException("Invalid session token", RuntimeException("boom"))

        val response = mapper.toResponse(exception)

        assertEquals(401, response.status)
        assertEquals("Bearer", response.getHeaderString("WWW-Authenticate"))
        assertEquals("AUTHENTICATION_FAILED", (response.entity as ProblemDetail).code)
    }
}
