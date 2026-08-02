package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.serialization

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.inject.Inject
import jakarta.ws.rs.ext.ParamConverter
import jakarta.ws.rs.ext.ParamConverterProvider
import jakarta.ws.rs.ext.Provider
import kotlin.io.encoding.Base64

/**
 * JAX-RS provider that supplies [Base64JsonParamConverter] for parameters annotated with [Base64Json].
 *
 * This is used for **input deserialization** of query parameters (e.g., `@QueryParam`).
 * For response body serialization, see [Base64JsonSerializer].
 */
@Provider
class Base64JsonParamConverterProvider
@Inject
constructor(
    private val objectMapper: ObjectMapper,
) : ParamConverterProvider {
    override fun <T : Any> getConverter(
        rawType: Class<T>,
        genericType: java.lang.reflect.Type?,
        annotations: Array<out Annotation>?,
    ): ParamConverter<T>? {
        // Only apply to classes annotated with a custom annotation
        return Base64JsonParamConverter(objectMapper, rawType)
            .takeIf { annotations?.any { annotation -> annotation is Base64Json } == true }
    }
}

/**
 * JAX-RS parameter converter that decodes Base64-encoded JSON query parameters.
 *
 * Used for **input deserialization** when a query parameter contains a Base64-encoded JSON object.
 * The [fromString] method decodes the Base64 string and deserializes the JSON to the target type.
 *
 * Note: The [toString] method is provided for completeness but is typically unused,
 * as response body serialization is handled by [Base64JsonSerializer] instead.
 */
class Base64JsonParamConverter<T>(
    private val objectMapper: ObjectMapper,
    private val targetClass: Class<T>,
) : ParamConverter<T> {
    override fun fromString(value: String?): T? {
        if (value == null) return null
        return Base64
            .decode(value)
            .decodeToString()
            .let { objectMapper.readValue(it, targetClass) }
    }

    override fun toString(value: T?): String? {
        if (value == null) return null
        return objectMapper
            .writeValueAsString(value)
            .let { it.toByteArray() }
            .let { Base64.encode(it) }
    }
}

/**
 * Marks a `@QueryParam` for Base64-encoded JSON **input** handling:
 * [Base64JsonParamConverterProvider] supplies a [Base64JsonParamConverter] that decodes the
 * Base64 string and deserializes the JSON to the target type.
 *
 * This annotation is intentionally **input-only**. For **output** serialization, annotate the
 * response DTO getter directly with `@get:JsonSerialize(using = Base64JsonSerializer::class)`.
 * A `@JacksonAnnotationsInside` meta-annotation was previously used for output too, but Jackson
 * stopped unwrapping it under Kotlin 2.4 / Quarkus 3.37, silently emitting raw JSON cursors
 * instead of Base64.
 *
 * This keeps cursor values opaque to API consumers while remaining structured internally.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Base64Json
