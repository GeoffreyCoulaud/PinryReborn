package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.migration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The indexes a hot query depends on, pinned in the current schema (`MigrationDirectory.currentIndexes`) so a
 * later migration dropping one fails here.
 *
 * Two of them serve the periodic garbage collection cutoff sweeps, which filter on columns that accumulate
 * with activity, so the spec (`docs/specs/2026-07-27-periodic-gc.md` section 11) requires supporting indexes
 * to keep each sweep a targeted scan rather than O(n) over a growing table. The third serves the task queue's
 * claim query (`docs/specs/2026-08-13-persistence-p2-debt.md` section 3.3).
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

    // Pinned down to the column directions, unlike the two above: an all-ascending replacement would
    // silently reintroduce the temp B-tree that `claimNext`'s mixed ORDER BY needs this index to avoid.
    private val taskClaimIndex =
        Regex(
            """create\s+index\s+ix_tasks_claim\s+on\s+tasks\s*\(\s*state\s*,\s*priority\s+desc\s*,""" +
                """\s*available_at\s+asc\s*,\s*id\s+asc\s*\)""",
            RegexOption.IGNORE_CASE,
        )

    @Test
    fun `Given the migration scripts, Then the claim index leads on state and keeps its sort directions`() {
        // Given
        val claimIndex = MigrationDirectory.currentIndexes["ix_tasks_claim"]

        // Then
        assertNotNull(
            claimIndex,
            "Expected ix_tasks_claim in the current schema; got ${MigrationDirectory.currentIndexes.keys}",
        )
        val statement = claimIndex?.statement.orEmpty()
        assertTrue(
            taskClaimIndex.containsMatchIn(statement),
            "Expected ix_tasks_claim on tasks (state, priority desc, available_at asc, id asc); got:\n$statement",
        )
        // A `where` clause would make it partial again, and so skipped for a bound state.
        assertFalse(
            statement.contains("where", ignoreCase = true),
            "Expected ix_tasks_claim to be non-partial; got:\n$statement",
        )
    }

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
