package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ProblemDetail
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

    @Test
    fun `Given IMAGE_DOES_NOT_EXIST, Then status is NOT_FOUND`() {
        assertEquals(Response.Status.NOT_FOUND, statusFor(ErrorCode.IMAGE_DOES_NOT_EXIST))
    }

    @Test
    fun `Given IMAGE_INSUFFICIENT_PERMISSIONS, Then status is FORBIDDEN`() {
        assertEquals(Response.Status.FORBIDDEN, statusFor(ErrorCode.IMAGE_INSUFFICIENT_PERMISSIONS))
    }

    @Test
    fun `Given IMAGE_TOO_LARGE, Then status is 413 REQUEST_ENTITY_TOO_LARGE`() {
        assertEquals(Response.Status.REQUEST_ENTITY_TOO_LARGE, statusFor(ErrorCode.IMAGE_TOO_LARGE))
    }

    @Test
    fun `Given IMAGE_INVALID, Then status is 422`() {
        // jakarta.ws.rs 4.0's Response.Status has no UNPROCESSABLE_ENTITY constant, so this
        // asserts the raw status code instead of going through Response.Status.fromStatusCode.
        val exception = BaseError(message = "boom", code = ErrorCode.IMAGE_INVALID)

        val response = mapper.toResponse(exception)

        assertEquals(422, response.status)
        val body = response.entity as ProblemDetail
        assertEquals("Unprocessable Entity", body.title)
        assertEquals(422, body.status)
        assertEquals("IMAGE_INVALID", body.code)
    }

    @Test
    fun `Given IMAGE_SOURCE_URL_INVALID, Then status is BAD_REQUEST`() {
        assertEquals(Response.Status.BAD_REQUEST, statusFor(ErrorCode.IMAGE_SOURCE_URL_INVALID))
    }

    @Test
    fun `Given IMAGE_RENDITION_SIZE_INVALID, Then status is BAD_REQUEST`() {
        assertEquals(Response.Status.BAD_REQUEST, statusFor(ErrorCode.IMAGE_RENDITION_SIZE_INVALID))
    }

    @Test
    fun `Given BOARD_DOES_NOT_EXIST, Then status is NOT_FOUND`() {
        assertEquals(Response.Status.NOT_FOUND, statusFor(ErrorCode.BOARD_DOES_NOT_EXIST))
    }

    @Test
    fun `Given BOARD_INSUFFICIENT_PERMISSIONS, Then status is FORBIDDEN`() {
        assertEquals(Response.Status.FORBIDDEN, statusFor(ErrorCode.BOARD_INSUFFICIENT_PERMISSIONS))
    }

    @Test
    fun `Given BOARD_NOT_SOFT_DELETED, Then status is CONFLICT`() {
        assertEquals(Response.Status.CONFLICT, statusFor(ErrorCode.BOARD_NOT_SOFT_DELETED))
    }

    @Test
    fun `Given BOARD_ALREADY_SOFT_DELETED, Then status is CONFLICT`() {
        assertEquals(Response.Status.CONFLICT, statusFor(ErrorCode.BOARD_ALREADY_SOFT_DELETED))
    }

    @Test
    fun `Given BOARD_INVALID_MEMBERSHIP, Then status is BAD_REQUEST`() {
        assertEquals(Response.Status.BAD_REQUEST, statusFor(ErrorCode.BOARD_INVALID_MEMBERSHIP))
    }

    @Test
    fun `Given REAUTHENTICATION_FAILED, Then status is FORBIDDEN`() {
        assertEquals(Response.Status.FORBIDDEN, statusFor(ErrorCode.REAUTHENTICATION_FAILED))
    }

    @Test
    fun `Given PASSWORD_PREVIOUSLY_USED, Then status is 422`() {
        val exception = BaseError(message = "boom", code = ErrorCode.PASSWORD_PREVIOUSLY_USED)
        val response = mapper.toResponse(exception)
        assertEquals(422, response.status)
        val body = response.entity as ProblemDetail
        assertEquals("Unprocessable Entity", body.title)
        assertEquals(422, body.status)
        assertEquals("PASSWORD_PREVIOUSLY_USED", body.code)
    }
}
