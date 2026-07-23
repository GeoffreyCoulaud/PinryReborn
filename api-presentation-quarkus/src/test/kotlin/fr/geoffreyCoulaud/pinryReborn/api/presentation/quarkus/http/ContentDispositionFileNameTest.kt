package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentDispositionFileNameTest {
    @Test
    fun `Given a username with quotes and CRLF, Then the ASCII filename contains neither`() {
        // Given
        val rawName = "ali\"ce\r\nadmin"

        // When
        val header = ContentDispositionFileName.headerValue(rawName, "export")

        // Then
        val ascii = extractAscii(header)
        assertFalse(ascii.contains('"'))
        assertFalse(ascii.contains('\r'))
        assertFalse(ascii.contains('\n'))
    }

    @Test
    fun `Given a username with path traversal, Then no slash survives`() {
        // Given
        val rawName = "../../etc/passwd"

        // When
        val header = ContentDispositionFileName.headerValue(rawName, "export")

        // Then
        val ascii = extractAscii(header)
        // No path separator survives, so the ASCII name cannot address another directory even
        // though it is a single opaque path segment. NOTE: literal dot-dot sequences are NOT
        // stripped by the mandated UNSAFE regex (dots are in the allowed character class) -- see
        // the final report for why that is a discrepancy between the plan's test name and its own
        // reference implementation.
        assertFalse(ascii.contains('/'))
        assertFalse(ascii.contains('\\'))
    }

    @Test
    fun `Given a non-ASCII username, Then the ASCII form is sanitized and the UTF-8 form percent-encoded`() {
        // Given
        val rawName = "café"

        // When
        val header = ContentDispositionFileName.headerValue(rawName, "export")

        // Then: 'é' is not in the ASCII-safe set, so it is dropped from the quoted filename and
        // percent-encoded (as its two UTF-8 bytes, 0xC3 0xA9) in the RFC 5987 extended form.
        assertEquals("caf", extractAscii(header))
        assertTrue(header.contains("filename*=UTF-8''caf%C3%A9"))
    }

    @Test
    fun `Given a username that sanitizes to nothing, Then the fallback is used`() {
        // Given
        val rawName = "!!!@@@###"

        // When
        val header = ContentDispositionFileName.headerValue(rawName, "export")

        // Then
        assertEquals("export", extractAscii(header))
    }

    @Test
    fun `Given an over-long username, Then the name is capped`() {
        // Given
        val rawName = "a".repeat(150)

        // When
        val header = ContentDispositionFileName.headerValue(rawName, "export")

        // Then
        assertEquals(100, extractAscii(header).length)
    }

    private fun extractAscii(header: String): String {
        val match = Regex("filename=\"([^\"]*)\"").find(header)
            ?: error("no ASCII filename segment found in: $header")
        return match.groupValues[1]
    }
}
