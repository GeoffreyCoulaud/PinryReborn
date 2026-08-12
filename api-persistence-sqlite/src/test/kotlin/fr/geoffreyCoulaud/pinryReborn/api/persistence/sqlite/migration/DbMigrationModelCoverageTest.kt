package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
 *
 * The no-op rule below catches a related failure: a migration Ebean cannot render is written as
 * `-- not supported: ...` and applies silently, enforcing nothing.
 *
 * Pairing is not content: a model file can exist and record none of its migration's indexes, which is
 * what `1.3.model.xml` did. The index-model rule below closes that gap.
 */
class DbMigrationModelCoverageTest {
    // Empty, and meant to stay so: writing a model file rewrites no `.sql`, so the checksum argument
    // `1.2` rested on never applied (`docs/adr/0009-unique-index-named-outcomes.md`, decision 5).
    private val handWritten = emptySet<String>()

    private val createIndexStatement =
        Regex("""create\s+(?:unique\s+)?index\s+(\w+)""", RegexOption.IGNORE_CASE)

    // Anchored on the element, not on the attribute: `<dropIndex indexName="..."/>` takes the index
    // back out of the prior model, so a name whose only record is its removal is not recorded at all.
    private val modelCreateIndexElement =
        Regex("""<createIndex\b[^>]*\bindexName="([^"]+)"""")

    // The loose probe for the extraction above, in the sense MigrationDirectory.locationsMatching describes.
    private val looseIndexCreation = Regex("""create\b.*\bindex\b""", RegexOption.IGNORE_CASE)

    private val createdIndexNames: Set<String> =
        namesMatching(createIndexStatement, MigrationDirectory.sqlScripts, MigrationDirectory::schemaOnly)

    private val modelledIndexNames: Set<String> =
        namesMatching(modelCreateIndexElement, MigrationDirectory.modelFiles, MigrationDirectory::textWithComments)

    @Test
    fun `Given the migration scripts, Then each one is backed by a generated model or documented here`() {
        val withoutModel =
            MigrationDirectory
                .sqlScripts
                .map { it.name.removeSuffix(".sql") }
                .filterNot { MigrationDirectory.modelFileFor(it).exists() }
        assertEquals(handWritten, withoutModel.toSet())
    }

    @Test
    fun `Given the migration directory, Then it is where this test expects it`() {
        // Guards against a silent pass if the working directory or the layout ever moves: an empty
        // listing would make the assertions above trivially true.
        assertEquals(true, MigrationDirectory.root.isDirectory)
    }

    @Test
    fun `Given the migration scripts, Then none carries an Ebean no-op marker`() {
        // Ebean's SQLite dialect writes "-- not supported: ..." (and nothing else) when it cannot
        // render a change: @Index(unique = true) becomes an unsupported ALTER TABLE ADD CONSTRAINT
        // UNIQUE, so the migration applies silently and enforces nothing. Such a no-op must never be
        // committed. A unique index on SQLite uses @Index(definition = "create unique index ..."),
        // which the generator renders.
        val noOpMigrations =
            MigrationDirectory.sqlScripts.filter { script ->
                MigrationDirectory.textWithComments(script).lineSequence().any { it.startsWith("-- not supported") }
            }
        assertEquals(emptyList<File>(), noOpMigrations)
    }

    @Test
    fun `Given the migration scripts, Then every index they create is recorded in a migration model`() {
        // Given
        val unrecorded = createdIndexNames.filterNot { it in modelledIndexNames }.sorted()

        // Then
        assertEquals(emptyList<String>(), unrecorded)
    }

    @Test
    fun `Given the migration scripts, Then they create at least one index`() {
        // Guards the assertion above: a regex that stops matching leaves an empty set, trivially recorded in full.
        assertNotEquals(emptySet<String>(), createdIndexNames)
    }

    @Test
    fun `Given the migration scripts, Then the extraction reads every line that creates an index`() {
        // The guard above only proves non-emptiness: an extractor blind to one form still matches the others.

        // Given
        val extracted = MigrationDirectory.locationsMatching(createIndexStatement)

        // Then
        assertEquals(MigrationDirectory.locationsMatching(looseIndexCreation), extracted)
    }

    private fun namesMatching(
        pattern: Regex,
        files: List<File>,
        read: (File) -> String,
    ): Set<String> =
        files
            .flatMap { file -> pattern.findAll(read(file)).map { it.groupValues[1] } }
            .toSet()
}
