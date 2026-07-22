package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ExportImageExtensionTest {
    @ParameterizedTest
    @CsvSource(
        "image/jpeg,jpg",
        "image/png,png",
        "image/webp,webp",
        "image/gif,gif",
        "image/avif,avif",
        "application/x-thing,bin",
    )
    fun `Given a mime type, Then the archive extension matches`(mimeType: String, expected: String) {
        // Given
        // mimeType and expected are supplied by @CsvSource, covering all six when-branches
        // (five known MIME types plus the else fallback).

        // When
        val extension = ExportImageExtension.forMimeType(mimeType)

        // Then
        assertEquals(expected, extension)
    }
}
