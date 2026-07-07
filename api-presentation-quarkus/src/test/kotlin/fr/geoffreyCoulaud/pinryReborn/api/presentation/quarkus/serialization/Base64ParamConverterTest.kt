package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.serialization

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.io.encoding.Base64

class Base64JsonParamConverterTest {
    private val objectMapper = ObjectMapper()
    private val converter = Base64JsonParamConverter(objectMapper = objectMapper, targetClass = String::class.java)

    @Test
    fun `Given a null string, Then fromString returns null`() {
        // Given, When
        val result = converter.fromString(null)

        // Then
        assertNull(result)
    }

    @Test
    fun `Given a base64-encoded JSON string, Then fromString decodes and deserializes it`() {
        // Given
        val encoded = Base64.encode(objectMapper.writeValueAsString("hello").toByteArray())

        // When
        val result = converter.fromString(encoded)

        // Then
        assertEquals("hello", result)
    }

    @Test
    fun `Given a null value, Then toString returns null`() {
        // Given, When
        val result = converter.toString(null)

        // Then
        assertNull(result)
    }

    @Test
    fun `Given a non-null value, Then toString serializes and base64-encodes it`() {
        // Given, When
        val result = converter.toString("hello")

        // Then
        val decoded = Base64.decode(requireNotNull(result)).decodeToString()
        assertEquals("\"hello\"", decoded)
    }
}

class Base64JsonParamConverterProviderTest {
    private val objectMapper = ObjectMapper()
    private val provider = Base64JsonParamConverterProvider(objectMapper = objectMapper)

    @Test
    fun `Given null annotations, Then getConverter returns null`() {
        // Given, When
        val result = provider.getConverter(String::class.java, null, null)

        // Then
        assertNull(result)
    }

    @Test
    fun `Given annotations containing Base64Json, Then getConverter returns a converter`() {
        // Given
        val annotations = arrayOf<Annotation>(Base64Json())

        // When
        val result = provider.getConverter(String::class.java, null, annotations)

        // Then
        assertEquals(Base64JsonParamConverter::class.java, result?.javaClass)
    }

    @Test
    fun `Given annotations without Base64Json, Then getConverter returns null`() {
        // Given
        val annotations = arrayOf<Annotation>(NotBase64Json())

        // When
        val result = provider.getConverter(String::class.java, null, annotations)

        // Then
        assertNull(result)
    }
}

private annotation class NotBase64Json
