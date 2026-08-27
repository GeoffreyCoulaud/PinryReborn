package fr.geoffreyCoulaud.pinryReborn.api.domain.enums

import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `Given every state, Then the terminal ones are the four an export can end on`() {
        // Given / When
        val terminal = UserDataExportState.entries.filter { it.isTerminal }

        // Then: enumerated positively, so a state added later is neither terminal nor live and this
        // fails rather than silently admitting it to the sweep that deletes bytes
        assertEquals(
            listOf(
                UserDataExportState.FAILED,
                UserDataExportState.EXPIRED,
                UserDataExportState.DELETED,
                UserDataExportState.SUPERSEDED,
            ),
            terminal,
        )
    }

    @Test
    fun `Given a state a build can still leave, Then it is not terminal`() {
        // Given / When / Then: the reclaiming sweep deletes the bytes of a terminal row, so READY
        // read into this set destroys the archive a user can still download
        assertFalse(UserDataExportState.PENDING.isTerminal)
        assertFalse(UserDataExportState.READY.isTerminal)
    }

    @Test
    fun `Given every state, Then gone is terminal and FAILED is the terminal state that is not gone`() {
        // Given: isGone is expressed through isTerminal rather than beside it, so the two sets
        // cannot drift apart when a state is added

        // When
        val goneButNotTerminal = UserDataExportState.entries.filter { it.isGone && !it.isTerminal }
        val terminalButNotGone = UserDataExportState.entries.filter { it.isTerminal && !it.isGone }

        // Then
        assertEquals(emptyList<UserDataExportState>(), goneButNotTerminal)
        assertEquals(listOf(UserDataExportState.FAILED), terminalButNotGone)

        // And: a seventh state would leave both predicates false without failing anything above, so
        // the count is what forces the next author to answer the question.
        assertEquals(6, UserDataExportState.entries.size)
    }
}
