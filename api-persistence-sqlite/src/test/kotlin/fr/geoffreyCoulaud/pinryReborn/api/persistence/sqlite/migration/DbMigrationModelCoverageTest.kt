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
 * Pairing is not content: a model file can exist and still record none of its migration's indexes,
 * which is what `1.3.model.xml` did for the three `ix_tasks_*` indexes `1.3.sql` creates. The
 * index-model rule below closes that gap and admits no exemption
 * (`docs/adr/0009-unique-index-named-outcomes.md`, decision 5).
 */
class DbMigrationModelCoverageTest {
    private val migrationDirectory = File("src/main/resources/dbmigration")

    // Empty, and meant to stay so: `1.2` was the last entry and its model file now exists. Writing
    // one rewrites no `.sql`, so the checksum argument the entry rested on never applied
    // (`docs/adr/0009-unique-index-named-outcomes.md`, decision 5).
    private val handWritten = emptySet<String>()

    private val sqlScripts: List<File> =
        migrationDirectory
            .listFiles()
            ?.toList()
            .orEmpty()
            .filter { it.name.endsWith(".sql") }

    private val createIndexStatement =
        Regex("""create\s+(?:unique\s+)?index\s+(\w+)""", RegexOption.IGNORE_CASE)

    // Anchored on the element, not on the attribute: `<dropIndex indexName="..."/>` takes the index
    // back out of the prior model, so a name whose only record is its removal is not recorded at all.
    private val modelCreateIndexElement =
        Regex("""<createIndex\b[^>]*\bindexName="([^"]+)"""")

    // Deliberately loose, and only ever compared against the extraction above: it over-matches so that
    // a narrower extractor, blind to a form the migrations still use, shows up as a difference.
    private val looseIndexCreation = Regex("""create\b.*\bindex\b""", RegexOption.IGNORE_CASE)

    private val createdIndexNames: Set<String> =
        namesMatching(createIndexStatement, sqlScripts)

    private val modelledIndexNames: Set<String> =
        namesMatching(
            modelCreateIndexElement,
            File(migrationDirectory, "model")
                .listFiles()
                ?.toList()
                .orEmpty()
                .filter { it.name.endsWith(".model.xml") },
        )

    @Test
    fun `Given the migration scripts, Then each one is backed by a generated model or documented here`() {
        val withoutModel =
            sqlScripts
                .map { it.name.removeSuffix(".sql") }
                .filterNot { File(migrationDirectory, "model/$it.model.xml").exists() }
        assertEquals(handWritten, withoutModel.toSet())
    }

    @Test
    fun `Given the migration directory, Then it is where this test expects it`() {
        // Guards against a silent pass if the working directory or the layout ever moves: an empty
        // listing would make the assertions above trivially true.
        assertEquals(true, migrationDirectory.isDirectory)
    }

    @Test
    fun `Given the migration scripts, Then none carries an Ebean no-op marker`() {
        // Ebean's SQLite dialect writes "-- not supported: ..." (and nothing else) when it cannot
        // render a change: @Index(unique = true) becomes an unsupported ALTER TABLE ADD CONSTRAINT
        // UNIQUE, so the migration applies silently and enforces nothing. Such a no-op must never be
        // committed. A unique index on SQLite uses @Index(definition = "create unique index ..."),
        // which the generator renders.
        val noOpMigrations =
            sqlScripts.filter { script ->
                script.readText().lineSequence().any { it.startsWith("-- not supported") }
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
        // Guards the assertion above against a silent pass: a regex that stops matching leaves an
        // empty set, which is trivially recorded in full.
        assertNotEquals(emptySet<String>(), createdIndexNames)
    }

    @Test
    fun `Given the migration scripts, Then the extraction reads every line that creates an index`() {
        // The guard above only proves the extraction is non-empty: an extractor blind to one form
        // still matches the others. Compared both ways, so a loose probe that narrows fails too.

        // Given
        val extracted = locationsMatching(createIndexStatement)

        // Then
        assertEquals(locationsMatching(looseIndexCreation), extracted)
    }

    private fun namesMatching(
        pattern: Regex,
        files: List<File>,
    ): Set<String> =
        files
            .flatMap { file -> pattern.findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()

    /** Where [pattern] matches, as `<file>:<line>` locators, sorted so a failure reads the same twice. */
    private fun locationsMatching(pattern: Regex): List<String> =
        sqlScripts
            .sortedBy { it.name }
            .flatMap { file ->
                file
                    .readText()
                    .lineSequence()
                    .withIndex()
                    .filter { (_, line) -> pattern.containsMatchIn(line) }
                    .map { (index, _) -> "${file.name}:${index + 1}" }
            }
}
