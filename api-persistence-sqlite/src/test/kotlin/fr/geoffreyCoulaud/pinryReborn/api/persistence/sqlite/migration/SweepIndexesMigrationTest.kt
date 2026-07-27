package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.migration

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The periodic garbage collection cutoff sweeps filter on columns that accumulate with activity, so the spec
 * (`docs/specs/2026-07-27-periodic-gc.md` section 11) requires supporting indexes to keep each sweep a
 * targeted scan rather than O(n) over a growing table. This test pins their presence in the
 * migration history so a future model edit cannot silently drop them.
 *
 * The assertion is form-independent: it ignores which Ebean annotation produced the index and the
 * generated index name, and checks only that some migration declares an index spanning the expected
 * table and column set.
 */
class SweepIndexesMigrationTest {
    private val migrationDirectory = File("src/main/resources/dbmigration")

    private val sessionTokenExpiresAtIndex =
        Regex(
            """create\s+(unique\s+)?index\s+\S+\s+on\s+session_tokens\s*\(\s*expires_at\s*\)""",
            RegexOption.IGNORE_CASE,
        )

    // Column order matters for the terminal-task sweep: `state` is the most selective predicate
    // (three terminal values out of the full state space) and leads, `when_modified` follows.
    private val taskStateWhenModifiedIndex =
        Regex(
            """create\s+(unique\s+)?index\s+\S+\s+on\s+tasks\s*\(\s*state\s*,\s*when_modified\s*\)""",
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
    fun `Given the migration scripts, Then a composite index targets tasks state and when_modified`() {
        // Given
        val migrations = readAllMigrations()

        // Then
        assertTrue(
            taskStateWhenModifiedIndex.containsMatchIn(migrations),
            "Expected a composite index on tasks (state, when_modified); got:\n$migrations",
        )
    }

    @Test
    fun `Given the migration directory, Then it is where this test expects it`() {
        // Guards against a silent pass if the working directory or the layout ever moves: an empty
        // listing would make the assertions above trivially pass.
        assertTrue(migrationDirectory.isDirectory, "${migrationDirectory.path} must be a directory")
    }

    private fun readAllMigrations(): String =
        migrationDirectory
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".sql") }
            .joinToString(separator = "\n") { it.readText() }
}
