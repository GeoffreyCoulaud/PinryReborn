package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.serialization

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import kotlin.io.encoding.Base64

/**
 * Jackson serializer that encodes objects as Base64-encoded JSON strings.
 *
 * Used for **output serialization** of response DTO getters annotated with
 * `@get:JsonSerialize(using = Base64JsonSerializer::class)`.
 * This makes values like pagination cursors opaque to API consumers.
 *
 * For input deserialization of query parameters, see [Base64JsonParamConverter].
 */
class Base64JsonSerializer : StdSerializer<Any>(Any::class.java) {
    override fun serialize(value: Any?, gen: JsonGenerator, provider: SerializerProvider) {
        if (value == null) {
            gen.writeNull()
            return
        }
        // Use a fresh ObjectMapper to serialize the value to JSON without triggering this serializer again
        val mapper = gen.codec as? ObjectMapper ?: ObjectMapper()
        val jsonString = mapper.writeValueAsString(value)
        val base64String = Base64.encode(jsonString.toByteArray())
        gen.writeString(base64String)
    }
}
