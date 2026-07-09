package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.UriInfo
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.net.URI

class LoggingRequestResponseFilterTest {
    private val objectMapper = ObjectMapper()
    private val filter = LoggingRequestResponseFilter(objectMapper = objectMapper)

    @Test
    fun `Given a request without an entity, Then requestFilter does not read the body`() {
        // Given
        val ctx = mockk<ContainerRequestContext>()
        val uriInfo = mockk<UriInfo>()
        every { ctx.method } returns "GET"
        every { ctx.uriInfo } returns uriInfo
        every { uriInfo.requestUri } returns URI.create("http://localhost/api/v1/pins")
        every { ctx.headers } returns MultivaluedHashMap<String, String>().apply { add("Accept", "application/json") }
        every { ctx.hasEntity() } returns false

        // When, Then
        assertDoesNotThrow { filter.requestFilter(ctx) }
    }

    @Test
    fun `Given a request with an entity, Then requestFilter reads and restores the body`() {
        // Given
        val ctx = mockk<ContainerRequestContext>()
        val uriInfo = mockk<UriInfo>()
        val bodyBytes = """{"name":"test"}""".toByteArray()
        every { ctx.method } returns "POST"
        every { ctx.uriInfo } returns uriInfo
        every { uriInfo.requestUri } returns URI.create("http://localhost/api/v1/users")
        every {
            ctx.headers
        } returns MultivaluedHashMap<String, String>().apply { add("Content-Type", "application/json") }
        every { ctx.hasEntity() } returns true
        every { ctx.entityStream } returns ByteArrayInputStream(bodyBytes)
        every { ctx.entityStream = any() } answers { }

        // When, Then
        assertDoesNotThrow { filter.requestFilter(ctx) }
    }

    @Test
    fun `Given a response without an entity, Then responseFilter does not log a body`() {
        // Given
        val ctx = mockk<ContainerResponseContext>()
        every { ctx.status } returns 204
        every { ctx.headers } returns MultivaluedHashMap<String, Any>().apply { add("X-Test", "1") }
        every { ctx.hasEntity() } returns false

        // When, Then
        assertDoesNotThrow { filter.responseFilter(ctx) }
    }

    @Test
    fun `Given a response with an entity, Then responseFilter logs the body`() {
        // Given
        val ctx = mockk<ContainerResponseContext>()
        every { ctx.status } returns 200
        every {
            ctx.headers
        } returns MultivaluedHashMap<String, Any>().apply { add("Content-Type", "application/json") }
        every { ctx.hasEntity() } returns true
        every { ctx.entity } returns mapOf("ok" to true)

        // When, Then
        assertDoesNotThrow { filter.responseFilter(ctx) }
    }

    @Test
    fun `Given a non-JSON-serializable entity, Then responseFilter logs a placeholder instead of throwing`() {
        // Given - a bare Any() has no bean properties, so Jackson's default mapper refuses it
        // (same failure shape as a StreamingOutput lambda, e.g. the image endpoints' raw bytes).
        val ctx = mockk<ContainerResponseContext>()
        every { ctx.status } returns 200
        every {
            ctx.headers
        } returns MultivaluedHashMap<String, Any>().apply { add("Content-Type", "application/octet-stream") }
        every { ctx.hasEntity() } returns true
        every { ctx.entity } returns Any()

        // When, Then
        assertDoesNotThrow { filter.responseFilter(ctx) }
    }
}
