package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security

import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.MalformedReauthenticationError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ReauthenticationError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

class ReauthenticationHeaderTest {
    @Test
    fun `Given a valid password header, Then it returns the decoded value`() {
        val header = "password " + Base64.getUrlEncoder().encodeToString("secret".toByteArray())
        val factor = ReauthenticationHeader.parsePasswordFactor(header)
        assertEquals("secret", factor)
    }

    @Test
    fun `Given no header, Then it throws ReauthenticationError`() {
        assertThrows(ReauthenticationError::class.java) {
            ReauthenticationHeader.parsePasswordFactor(null)
        }
    }

    @Test
    fun `Given an unsupported factor kind, Then it throws MalformedReauthenticationError`() {
        assertThrows(MalformedReauthenticationError::class.java) {
            ReauthenticationHeader.parsePasswordFactor("totp abc")
        }
    }

    @Test
    fun `Given a header with no value, Then it throws MalformedReauthenticationError`() {
        assertThrows(MalformedReauthenticationError::class.java) {
            ReauthenticationHeader.parsePasswordFactor("password")
        }
    }

    @Test
    fun `Given a header with invalid base64url, Then it throws MalformedReauthenticationError`() {
        assertThrows(MalformedReauthenticationError::class.java) {
            ReauthenticationHeader.parsePasswordFactor("password !!!")
        }
    }
}
