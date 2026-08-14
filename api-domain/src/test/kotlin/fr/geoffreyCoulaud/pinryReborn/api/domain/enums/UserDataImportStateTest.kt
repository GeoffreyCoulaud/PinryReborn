package fr.geoffreyCoulaud.pinryReborn.api.domain.enums

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UserDataImportStateTest {
    @Test
    fun `Given every state, Then the active ones are the three the one-import-at-a-time index tests`() {
        // Given / When
        val active = UserDataImportState.entries.filter { it.isActive }

        // Then
        assertEquals(
            listOf(
                UserDataImportState.AWAITING_ARCHIVE,
                UserDataImportState.PENDING,
                UserDataImportState.RUNNING,
            ),
            active,
        )
    }

    @Test
    fun `Given every state, Then the terminal ones are the four an import can end on`() {
        // Given / When
        val terminal = UserDataImportState.entries.filter { it.isTerminal }

        // Then
        assertEquals(
            listOf(
                UserDataImportState.COMPLETED,
                UserDataImportState.FAILED,
                UserDataImportState.CANCELLED,
                UserDataImportState.ABANDONED,
            ),
            terminal,
        )
    }

    @Test
    fun `Given every state, Then it is active or terminal and never both`() {
        // Given: the two accessors partition the enum, which is what lets a consumer read one of them

        // When
        val neitherOrBoth = UserDataImportState.entries.filter { it.isActive == it.isTerminal }

        // Then
        assertEquals(emptyList<UserDataImportState>(), neitherOrBoth)
    }
}
