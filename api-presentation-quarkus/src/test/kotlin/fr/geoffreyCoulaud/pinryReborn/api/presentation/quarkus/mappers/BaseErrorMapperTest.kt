package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BaseError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ErrorCode
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BaseErrorMapperTest {
    private val mapper = BaseErrorMapper().apply {
        uriInfo = mockk<UriInfo>().also {
            every { it.path } returns "/api/v1/test"
        }
    }

    private fun statusFor(code: ErrorCode): Response.Status {
        val exception = BaseError(message = "boom", code = code)
        val response = mapper.toResponse(exception)
        return Response.Status.fromStatusCode(response.status)
    }

    @Test
    fun `Given USERNAME_ALREADY_EXISTS, Then status is CONFLICT`() {
        assertEquals(Response.Status.CONFLICT, statusFor(ErrorCode.USERNAME_ALREADY_EXISTS))
    }

    @Test
    fun `Given PIN_DOES_NOT_EXIST, Then status is NOT_FOUND`() {
        assertEquals(Response.Status.NOT_FOUND, statusFor(ErrorCode.PIN_DOES_NOT_EXIST))
    }

    @Test
    fun `Given PIN_INSUFFICIENT_PERMISSIONS, Then status is FORBIDDEN`() {
        assertEquals(Response.Status.FORBIDDEN, statusFor(ErrorCode.PIN_INSUFFICIENT_PERMISSIONS))
    }

    @Test
    fun `Given PIN_NOT_SOFT_DELETED, Then status is CONFLICT`() {
        assertEquals(Response.Status.CONFLICT, statusFor(ErrorCode.PIN_NOT_SOFT_DELETED))
    }

    @Test
    fun `Given PIN_ALREADY_SOFT_DELETED, Then status is CONFLICT`() {
        assertEquals(Response.Status.CONFLICT, statusFor(ErrorCode.PIN_ALREADY_SOFT_DELETED))
    }

    @Test
    fun `Given SEARCH_EMPTY_QUERY, Then status is BAD_REQUEST`() {
        assertEquals(Response.Status.BAD_REQUEST, statusFor(ErrorCode.SEARCH_EMPTY_QUERY))
    }

    @Test
    fun `Given INVALID_LOGIN, Then status is BAD_REQUEST`() {
        assertEquals(Response.Status.BAD_REQUEST, statusFor(ErrorCode.INVALID_LOGIN))
    }

    @Test
    fun `Given USER_DOES_NOT_EXIST, Then status is UNAUTHORIZED`() {
        assertEquals(Response.Status.UNAUTHORIZED, statusFor(ErrorCode.USER_DOES_NOT_EXIST))
    }

    @Test
    fun `Given INVALID_PASSWORD, Then status is UNAUTHORIZED`() {
        assertEquals(Response.Status.UNAUTHORIZED, statusFor(ErrorCode.INVALID_PASSWORD))
    }

    @Test
    fun `Given INVALID_HTTP_AUTHORIZATION_SCHEME, Then status is UNAUTHORIZED`() {
        assertEquals(Response.Status.UNAUTHORIZED, statusFor(ErrorCode.INVALID_HTTP_AUTHORIZATION_SCHEME))
    }
}
