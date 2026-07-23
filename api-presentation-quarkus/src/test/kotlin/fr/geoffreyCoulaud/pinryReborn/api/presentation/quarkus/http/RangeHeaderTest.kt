package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RangeHeaderTest {
    private val totalSize = 1000L

    @Test
    fun `Given no header, Then the full body is served`() {
        // Given / When
        val range = RangeHeader.parse(null, totalSize)

        // Then
        assertNull(range)
    }

    @Test
    fun `Given an open-ended range, Then it runs to the last byte`() {
        // Given / When
        val range = RangeHeader.parse("bytes=100-", totalSize)

        // Then
        assertEquals(ByteRange(100, totalSize - 1), range)
    }

    @Test
    fun `Given a closed range, Then both bounds are honoured`() {
        // Given / When
        val range = RangeHeader.parse("bytes=100-200", totalSize)

        // Then
        assertEquals(ByteRange(100, 200), range)
    }

    @Test
    fun `Given an end beyond the size, Then it is clamped`() {
        // Given / When
        val range = RangeHeader.parse("bytes=100-999999", totalSize)

        // Then
        assertEquals(ByteRange(100, totalSize - 1), range)
    }

    @Test
    fun `Given a multi-range header, Then it is ignored and the full body is served`() {
        // Given / When
        val range = RangeHeader.parse("bytes=0-10,20-30", totalSize)

        // Then
        assertNull(range)
    }

    @Test
    fun `Given a suffix range, Then it is ignored and the full body is served`() {
        // Given / When
        val range = RangeHeader.parse("bytes=-500", totalSize)

        // Then
        assertNull(range)
    }

    @Test
    fun `Given a start past the end of the file, Then it is unsatisfiable`() {
        // Given / When / Then
        val exception = assertThrows(RangeNotSatisfiableException::class.java) {
            RangeHeader.parse("bytes=1000-", totalSize)
        }
        assertEquals(totalSize, exception.totalSize)
    }

    @Test
    fun `Given a malformed header, Then it is ignored`() {
        // Given / When
        val range = RangeHeader.parse("not a range at all", totalSize)

        // Then
        assertNull(range)
    }
}
