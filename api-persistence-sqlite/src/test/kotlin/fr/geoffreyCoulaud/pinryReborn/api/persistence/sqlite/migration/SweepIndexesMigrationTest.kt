package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.migration

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The periodic garbage collection cutoff sweeps filter on columns that accumulate with activity, so the spec
 * (`docs/specs/2026-07-27-periodic-gc.md` section 11) requires supporting indexes to keep each sweep a
 * targeted scan rather than O(n) over a growing table. This test pins their presence in the current
 * schema, `MigrationDirectory.currentIndexes`, so a later migration dropping one fails here.
 *
 * The assertion is form-independent: it ignores which Ebean annotation produced the index and the
 * generated index name, and checks only that some migration declares an index spanning the expected
 * table and column set.
 */
class SweepIndexesMigrationTest {
    private val sessionTokenExpiresAtIndex =
        Regex(
            """create\s+(unique\s+)?index\s+\S+\s+on\s+session_tokens\s*\(\s*expires_at\s*\)""",
            RegexOption.IGNORE_CASE,
        )

    // Column order matters for the terminal-task sweep: `state` is the most selective predicate
    // (three terminal values out of the full state space) and leads, `terminal_state_at` follows.
    private val taskStateTerminalStateAtIndex =
        Regex(
            """create\s+(unique\s+)?index\s+\S+\s+on\s+tasks\s*\(\s*state\s*,\s*terminal_state_at\s*\)""",
            RegexOption.IGNORE_CASE,
        )

    @Test
    fun `Given the migration scripts, Then an index targets session_tokens expires_at`() {
        // Given
        val migrations = readAllMigrations()

        // Then
        assertTrue(
            sessionTokenExpiresAtIndex.containsMatchIn(migrations),
            "Expected an index on session_tokens (expires_at); got:\n$migrations",
        )
    }

    @Test
    fun `Given the migration scripts, Then a composite index targets tasks state and terminal_state_at`() {
        // Given
        val migrations = readAllMigrations()

        // Then
        assertTrue(
            taskStateTerminalStateAtIndex.containsMatchIn(migrations),
            "Expected a composite index on tasks (state, terminal_state_at); got:\n$migrations",
        )
    }

    /**
     * The statements the history leaves in place, not every statement it ever carried: a later migration dropping
     * one of these indexes has to fail this test, which reading the whole history concatenated cannot do.
     */
    private fun readAllMigrations(): String =
        MigrationDirectory.currentIndexes.values.joinToString(separator = "\n") { it.statement }
}
