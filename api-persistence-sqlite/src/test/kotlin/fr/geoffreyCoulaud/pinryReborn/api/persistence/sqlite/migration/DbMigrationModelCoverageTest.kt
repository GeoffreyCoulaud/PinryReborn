package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every migration Ebean generates is paired with a `model/<version>.model.xml` recording the schema
 * state it produces. A `.sql` without one was written by hand, so its change exists nowhere in the
 * model: a later `generateDbMigration` cannot see it, and will happily drop or duplicate it.
 *
 * Before adding an entry to [handWritten], check that the generator really cannot express the change.
 * It expresses more than it first appears: `io.ebean.annotation.Index` carries a `definition`
 * attribute holding raw index DDL, which covers partial and expression indexes, and the attribute is
 * part of the migration model (`CreateIndex.definition`, diffed by `MIndex.compare`). Read the source
 * rather than assuming.
 */
class DbMigrationModelCoverageTest {
    private val migrationDirectory = File("src/main/resources/dbmigration")

    private val handWritten =
        setOf(
            // Case-insensitive unique index on users.name. Predates this rule; `@Index(definition = ...)`
            // on UserModel would express it today, but rewriting an applied migration changes its
            // checksum and breaks startup. Cleared when the history is flattened at beta (backlog).
            "1.2",
        )

    @Test
    fun `Given the migration scripts, Then each one is backed by a generated model or documented here`() {
        // Given
        val versions =
            migrationDirectory
                .listFiles()
                ?.toList()
                .orEmpty()
                .filter { it.name.endsWith(".sql") }
                .map { it.name.removeSuffix(".sql") }

        // When
        val withoutModel =
            versions.filterNot { File(migrationDirectory, "model/$it.model.xml").exists() }

        // Then
        assertEquals(handWritten, withoutModel.toSet())
    }

    @Test
    fun `Given the migration directory, Then it is where this test expects it`() {
        // Guards against a silent pass if the working directory or the layout ever moves: an empty
        // listing would make the assertion above trivially true.
        assertEquals(true, migrationDirectory.isDirectory)
    }

    @Test
    fun `Given the migration scripts, Then none is an Ebean no-op`() {
        // Ebean's SQLite dialect writes "-- not supported: ..." (and emits nothing else) when it
        // cannot render a change: @Index(unique = true) becomes an unsupported
        // ALTER TABLE ADD CONSTRAINT UNIQUE, so the migration applies silently and enforces nothing.
        // Such a no-op must never be committed. A unique index on SQLite uses
        // @Index(definition = "create unique index ..."), which the generator renders.
        val noOps =
            migrationDirectory
                .listFiles()
                ?.toList()
                .orEmpty()
                .filter { it.name.endsWith(".sql") }
                .filter { file ->
                    file.readText().lineSequence().any { it.contains("-- not supported", ignoreCase = true) }
                }
        assertEquals(emptyList<File>(), noOps)
    }
}
