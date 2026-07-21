package fr.geoffreyCoulaud.pinryReborn.api.usecases

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class TokenHasherTest {
    @Test
    fun `Given a known input, Then sha256 returns its lowercase hex digest`() {
        // SHA-256 of "abc"
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            TokenHasher.sha256("abc"),
        )
    }

    @Test
    fun `Given two different inputs, Then their digests differ`() {
        assertNotEquals(TokenHasher.sha256("token-a"), TokenHasher.sha256("token-b"))
    }
}
