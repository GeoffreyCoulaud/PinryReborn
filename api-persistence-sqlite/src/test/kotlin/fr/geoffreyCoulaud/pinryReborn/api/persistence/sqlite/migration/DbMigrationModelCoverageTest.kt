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
 *
 * A name is not the DDL either. Ebean harvests a model from the annotations, so its `definition` and
 * those annotations agree by construction and neither is compared to the statement the `.sql` ran. The
 * definition rule below is what ties the three together.
 */
class DbMigrationModelCoverageTest {
    // Empty, and meant to stay so: writing a model file rewrites no `.sql`, so the checksum argument
    // `1.2` rested on never applied (`docs/adr/0009-unique-index-named-outcomes.md`, decision 5).
    private val handWritten = emptySet<String>()

    private val createIndexStatement =
        Regex("""create\s+(?:unique\s+)?index\s+(\w+)""", RegexOption.IGNORE_CASE)

    // Anchored on the element, not on the attribute: `<dropIndex indexName="..."/>` takes the index
    // back out of the prior model, so a name whose only record is its removal is not recorded at all.
    private val modelCreateIndexElement = Regex("""<createIndex\b[^>]*>""")

    // Read from the whole element rather than in one pattern, so the pairing does not turn on the
    // order the generator happens to write the attributes in.
    private val indexNameAttribute = Regex("""\bindexName="([^"]+)"""")
    private val definitionAttribute = Regex("""\bdefinition="([^"]+)"""")
    private val tableNameAttribute = Regex("""\btableName="([^"]*)"""")
    private val columnsAttribute = Regex("""\bcolumns="([^"]*)"""")
    private val uniqueAttribute = Regex("""\bunique="true"""")

    // A definition on any other element would be counted by the loose probe and read by no extractor.
    private val definitionOnCreateIndex = Regex("""<createIndex\b[^>]*\bdefinition="""")

    // The loose probes for the extractions above, in the sense MigrationDirectory.locationsMatching describes.
    private val looseIndexCreation = Regex("""create\b.*\bindex\b""", RegexOption.IGNORE_CASE)
    private val looseDefinitionAttribute = Regex("""definition="""")

    private val whitespaceRun = Regex("""\s+""")

    /** The create-index statements each `.sql` applies, keyed by migration version then by index name. */
    private val appliedIndexStatements: Map<String, Map<String, String>> =
        MigrationDirectory.sqlScripts.associate { file ->
            file.name.removeSuffix(".sql") to createIndexStatementsIn(file)
        }

    /** Every `<createIndex>` the model files declare, in reading order. */
    private val modelIndexes: List<ModelIndex> =
        MigrationDirectory.modelFiles.flatMap { file ->
            val version = file.name.removeSuffix(".model.xml")
            modelCreateIndexElement
                .findAll(MigrationDirectory.textWithComments(file))
                .map { element ->
                    ModelIndex(
                        version = version,
                        name = indexNameAttribute.find(element.value)?.groupValues?.get(1).orEmpty(),
                        definition = definitionAttribute.find(element.value)?.groupValues?.get(1),
                        tableName = tableNameAttribute.find(element.value)?.groupValues?.get(1).orEmpty(),
                        columns = columnsAttribute.find(element.value)?.groupValues?.get(1).orEmpty(),
                        unique = uniqueAttribute.containsMatchIn(element.value),
                    )
                }.toList()
        }

    private val createdIndexNames: Set<String> = appliedIndexStatements.values.flatMap { it.keys }.toSet()

    private val modelledIndexNames: Set<String> = modelIndexes.map { it.name }.toSet()

    private val definedIndexCount: Int = modelIndexes.count { it.definition != null }

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

    @Test
    fun `Given a model index, Then what it records repeats the statement its migration applied`() {
        // Every element is paired, with no branch that skips one: deleting a `definition` attribute leaves the
        // column list as the claim, and a partial or expression index cannot be spelled that way, so it fails.

        // Given
        val disagreeing =
            modelIndexes
                .mapNotNull { index ->
                    val recorded = index.definition ?: columnListStatement(index)
                    val applied = appliedStatementFor(index)
                    if (applied != null && normalised(applied) == normalised(recorded)) {
                        null
                    } else {
                        "${index.version}.model.xml records ${index.name} as [$recorded], " +
                            "${appliedDescription(index)}"
                    }
                }.sorted()

        // Then
        assertEquals(emptyList<String>(), disagreeing)
    }

    @Test
    fun `Given the migration models, Then no definition attribute sits outside a createIndex`() {
        // Guards the count above, which a definition on another element and a truncated extraction cancel out of.

        // Given
        val onCreateIndex = modelLocationsMatching(definitionOnCreateIndex)

        // Then
        assertEquals(modelLocationsMatching(looseDefinitionAttribute), onCreateIndex)
    }

    @Test
    fun `Given the migration models, Then at least one of them carries an index definition`() {
        // Guards the assertion above: an extraction that reads no definition pairs nothing and passes.
        assertNotEquals(0, definedIndexCount)
    }

    @Test
    fun `Given the migration models, Then the extraction reads every definition attribute they carry`() {
        // Given
        val loosely =
            MigrationDirectory.modelFiles.sumOf { file ->
                looseDefinitionAttribute.findAll(MigrationDirectory.textWithComments(file)).count()
            }

        // Then
        assertEquals(loosely, definedIndexCount)
    }

    /**
     * The create-index statements [file] applies, keyed by index name: each runs from the `create`
     * keyword to its terminator, which is the text a model's `definition` has to answer to.
     */
    private fun createIndexStatementsIn(file: File): Map<String, String> {
        val schema = MigrationDirectory.schemaOnly(file)
        return createIndexStatement
            .findAll(schema)
            .associate { match ->
                val terminator = schema.indexOf(';', match.range.first)
                val end = if (terminator < 0) schema.length else terminator
                match.groupValues[1] to schema.substring(match.range.first, end)
            }
    }

    /**
     * Whitespace runs and the statement terminator are the only differences forgiven. Case is not: SQLite
     * compares string literals case-sensitively, so a `'PENDING'` predicate is not a `'pending'` one.
     */
    private fun normalised(statement: String): String =
        statement.replace(whitespaceRun, " ").trim().removeSuffix(";").trim()

    /** The statement a `<createIndex>` claims through its column list alone, which is all Ebean renders from it. */
    private fun columnListStatement(index: ModelIndex): String =
        "create ${if (index.unique) "unique " else ""}index ${index.name} " +
            "on ${index.tableName} (${index.columns})"

    private fun appliedStatementFor(index: ModelIndex): String? =
        appliedIndexStatements[index.version]?.get(index.name)

    /** Names which of the two absences a failure met, so the reader is not sent to a file that does not exist. */
    private fun appliedDescription(index: ModelIndex): String {
        val applied = appliedStatementFor(index)
        return when {
            applied != null -> "${index.version}.sql applies [$applied]"
            index.version !in appliedIndexStatements -> "there is no ${index.version}.sql"
            else -> "${index.version}.sql creates no such index"
        }
    }

    private fun modelLocationsMatching(pattern: Regex): List<String> =
        MigrationDirectory.locationsMatching(
            MigrationDirectory.modelFiles,
            MigrationDirectory::textWithComments,
            pattern,
        )

    private data class ModelIndex(
        val version: String,
        val name: String,
        val definition: String?,
        val tableName: String,
        val columns: String,
        val unique: Boolean,
    )
}
