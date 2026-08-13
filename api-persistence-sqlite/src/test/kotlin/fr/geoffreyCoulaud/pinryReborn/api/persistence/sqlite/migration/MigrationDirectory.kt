package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.migration

import java.io.File

/**
 * The migration directory as its guards read it: one listing, one comment-stripping rule, one locator. Each guard
 * keeps its own extractors and assertions and shares only the reading, so a guard written later cannot end up
 * looking at a different set of files than the ones before it.
 */
internal object MigrationDirectory {
    private val lineComment = Regex("--.*")

    // Name and the rest of the statement in one pattern, matched over the whole file: `[^;]` cannot leave the
    // statement, so a statement split over several lines is read like one written on a single line.
    private val indexCreation =
        Regex("""create\s+(?:unique\s+)?index\s+(\w+)[^;]*""", RegexOption.IGNORE_CASE)

    // SQLite's form (sqlite.org/lang_dropindex.html); exercised by `1.19.sql`, which drops two indexes.
    private val indexRemoval =
        Regex("""drop\s+index\s+(?:if\s+exists\s+)?(\w+)""", RegexOption.IGNORE_CASE)

    private val versionNumber = Regex("""\d+""")

    val root = File("src/main/resources/dbmigration")

    val sqlScripts: List<File> =
        root
            .listFiles()
            ?.toList()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".sql") }

    /** The `model/<version>.model.xml` files, each recording the schema state its migration produces. */
    val modelFiles: List<File> =
        File(root, "model")
            .listFiles()
            ?.toList()
            .orEmpty()
            .filter { it.name.endsWith(".model.xml") }

    /** The model file [version] is paired with, whether or not it exists. */
    fun modelFileFor(version: String): File = File(root, "model/$version.model.xml")

    /**
     * The create-index statement each index name is left in by the last migration that touched it, keyed by name.
     * The history is append-only, so a guard asking what the schema holds today asks this rather than whether some
     * migration once said it: a drop-and-recreate pair would otherwise read as two live indexes.
     */
    val currentIndexes: Map<String, CreatedIndex> =
        sqlScripts
            .sortedBy { versionKeyOf(it) }
            .flatMap { indexEventsIn(it) }
            .fold(mutableMapOf()) { current, event ->
                val created = event.created
                if (created == null) current.remove(event.name) else current[event.name] = created
                current
            }

    /**
     * Every create-index statement the history carries, live or since dropped. A guard asking what the schema
     * holds wants [currentIndexes]; this one is for a guard whose subject is the extraction itself.
     */
    val allIndexCreations: List<CreatedIndex> =
        sqlScripts.sortedBy { versionKeyOf(it) }.flatMap { file -> indexEventsIn(file).mapNotNull { it.created } }

    /** What [file] does to the indexes, in the order it does it: a creation, or a removal as a null one. */
    private fun indexEventsIn(file: File): List<IndexEvent> {
        val schema = schemaOnly(file)
        val creations =
            indexCreation.findAll(schema).map { match ->
                IndexEvent(
                    position = match.range.first,
                    name = match.groupValues[1],
                    created = CreatedIndex(name = match.groupValues[1], file = file.name, statement = match.value),
                )
            }
        val removals =
            indexRemoval.findAll(schema).map { match ->
                IndexEvent(position = match.range.first, name = match.groupValues[1], created = null)
            }
        return (creations + removals).sortedBy { it.position }.toList()
    }

    /**
     * [file]'s version as a sortable key: the file name's own order puts `1.11` before `1.3`, and a
     * `<version>__dropsFor_<version>` name carries a second number that is not its own.
     */
    private fun versionKeyOf(file: File): String =
        versionNumber
            .findAll(file.name.removeSuffix(".sql").substringBefore("__"))
            .joinToString(".") { it.value.padStart(VERSION_NUMBER_WIDTH, '0') }

    /** A create-index statement as committed, with the migration it came from. */
    data class CreatedIndex(
        val name: String,
        val file: String,
        val statement: String,
    )

    private data class IndexEvent(
        val position: Int,
        val name: String,
        val created: CreatedIndex?,
    )

    private const val VERSION_NUMBER_WIDTH = 4

    /**
     * [file] as committed, comments included. An assertion over a `.sql` reads [schemaOnly] instead, or a
     * commented-out statement counts as schema; this one is for an assertion whose subject is the comment, and
     * for the model XML, which the SQL line-comment rule would corrupt.
     */
    fun textWithComments(file: File): String = file.readText()

    /** [file] with SQL line comments blanked, newlines kept so a locator still names the right line. */
    fun schemaOnly(file: File): String = textWithComments(file).replace(lineComment, "")

    /**
     * Where any of [patterns] matches, as `<file>:<line>` locators, sorted so a failure reads the same twice.
     * Called twice per guard: a narrow extractor's locations against a deliberately loose probe's, so a spelling
     * the extractor reads as nothing at all shows up as a difference instead of as silent agreement.
     */
    fun locationsMatching(vararg patterns: Regex): List<String> =
        locationsMatching(sqlScripts, ::schemaOnly, *patterns)

    /** The same over [files], read through [read]: the model files need [textWithComments], not [schemaOnly]. */
    fun locationsMatching(
        files: List<File>,
        read: (File) -> String,
        vararg patterns: Regex,
    ): List<String> =
        files
            .sortedBy { it.name }
            .flatMap { file ->
                read(file)
                    .lineSequence()
                    .withIndex()
                    .filter { (_, line) -> patterns.any { it.containsMatchIn(line) } }
                    .map { (index, _) -> "${file.name}:${index + 1}" }
            }
}
