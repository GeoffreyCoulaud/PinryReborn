package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.serialization

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.ObjectCodec
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class Base64JsonSerializerTest {
    private val serializer = Base64JsonSerializer()
    private val provider = mockk<SerializerProvider>()

    @Test
    fun `Given a null value, Then serialize writes null`() {
        // Given
        val gen = mockk<JsonGenerator>()
        every { gen.writeNull() } answers { }

        // When
        serializer.serialize(null, gen, provider)

        // Then
        verify(exactly = 1) { gen.writeNull() }
    }

    @Test
    fun `Given a non-null value and a codec that is an ObjectMapper, Then serialize reuses the codec`() {
        // Given
        val gen = mockk<JsonGenerator>()
        val codec = ObjectMapper()
        every { gen.codec } returns codec
        every { gen.writeString(any<String>()) } answers { }

        // When
        serializer.serialize(mapOf("key" to "value"), gen, provider)

        // Then
        verify(exactly = 1) { gen.writeString(any<String>()) }
    }

    @Test
    fun `Given a non-null value and a non-ObjectMapper codec, Then serialize falls back to a fresh mapper`() {
        // Given
        val gen = mockk<JsonGenerator>()
        every { gen.codec } returns mockk<ObjectCodec>()
        every { gen.writeString(any<String>()) } answers { }

        // When
        serializer.serialize(mapOf("key" to "value"), gen, provider)

        // Then
        verify(exactly = 1) { gen.writeString(any<String>()) }
    }
}
