package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.MultivaluedMap
import org.jboss.resteasy.reactive.server.ServerRequestFilter
import org.jboss.resteasy.reactive.server.ServerResponseFilter
import java.io.ByteArrayInputStream

class LoggingRequestResponseFilter(
    private val objectMapper: ObjectMapper,
) {
    private val logger = KotlinLogging.logger {}

    @ServerRequestFilter
    fun requestFilter(ctx: ContainerRequestContext) {
        logger.info { "In --> ${ctx.method.uppercase()} ${ctx.uriInfo.requestUri}" }
        logHeaders(ctx.headers)
        logRequestBody(ctx)
    }

    @ServerResponseFilter
    fun responseFilter(ctx: ContainerResponseContext) {
        logger.info { "Out --> ${ctx.status}" }
        logHeaders(ctx.headers)
        logResponseBody(ctx)
    }

    private fun logHeaders(headers: MultivaluedMap<String, out Any>) {
        val headersMap = headers.entries.associate { it.key to it.value }
        val headersString = objectMapper.writeValueAsString(headersMap)
        logger.info { "Headers: $headersString" }
    }

    private fun logRequestBody(ctx: ContainerRequestContext) {
        if (ctx.hasEntity()) {
            val bodyBytes = ctx.entityStream.readAllBytes()
            val bodyString = String(bodyBytes, Charsets.UTF_8)
            logger.info { "Body: $bodyString" }
            ctx.entityStream = ByteArrayInputStream(bodyBytes)
        }
    }

    // Not every response entity is JSON-serializable (e.g. the image endpoints hand back a
    // StreamingOutput lambda for the raw bytes); falling back to a placeholder keeps this
    // debug-only filter from turning an otherwise-successful response into a 500.
    private fun logResponseBody(ctx: ContainerResponseContext) {
        if (ctx.hasEntity()) {
            val bodyString = runCatching { objectMapper.writeValueAsString(ctx.entity) }
                .getOrElse { "<unloggable body: ${ctx.entity::class.simpleName}>" }
            logger.info { "Body: $bodyString" }
        }
    }
}
