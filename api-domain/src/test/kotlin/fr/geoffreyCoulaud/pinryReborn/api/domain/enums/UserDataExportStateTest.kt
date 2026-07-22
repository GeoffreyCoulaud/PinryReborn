package fr.geoffreyCoulaud.pinryReborn.api.domain.enums

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UserDataExportStateTest {
    @Test
    fun `Given a destroyed state, Then it is gone`() {
        // Given / When / Then
        assertTrue(UserDataExportState.EXPIRED.isGone)
        assertTrue(UserDataExportState.DELETED.isGone)
        assertTrue(UserDataExportState.SUPERSEDED.isGone)
    }

    @Test
    fun `Given a live or failed state, Then it is not gone`() {
        // Given / When / Then
        assertFalse(UserDataExportState.PENDING.isGone)
        assertFalse(UserDataExportState.READY.isGone)
        assertFalse(UserDataExportState.FAILED.isGone)
    }
}
