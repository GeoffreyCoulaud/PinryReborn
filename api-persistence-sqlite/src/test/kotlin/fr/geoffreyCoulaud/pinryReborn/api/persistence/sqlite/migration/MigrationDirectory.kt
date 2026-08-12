package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.migration

import java.io.File

/**
 * The migration directory as its guards read it: one listing, one comment-stripping rule, one locator. Each guard
 * keeps its own extractors and assertions and shares only the reading, so a guard written later cannot end up
 * looking at a different set of files than the ones before it.
 */
internal object MigrationDirectory {
    private val lineComment = Regex("--.*")

    val root = File("src/main/resources/dbmigration")

    val sqlScripts: List<File> =
        root
            .listFiles()
            ?.toList()
            .orEmpty()
            .filter { it.name.endsWith(".sql") }

    /** The `model/<version>.model.xml` files, each recording the schema state its migration produces. */
    val modelFiles: List<File> =
        File(root, "model")
            .listFiles()
            ?.toList()
            .orEmpty()
            .filter { it.name.endsWith(".model.xml") }

    /** The model file [version] is paired with, whether or not it exists. */
    fun modelFileFor(version: String): File = File(root, "model/$version.model.xml")

    /** [file] as committed, comments included: an assertion whose subject is a comment reads this one. */
    fun rawText(file: File): String = file.readText()

    /** [file] with SQL line comments blanked, newlines kept so a locator still names the right line. */
    fun schemaOnly(file: File): String = rawText(file).replace(lineComment, "")

    /**
     * Where any of [patterns] matches, as `<file>:<line>` locators, sorted so a failure reads the same twice.
     * Called twice per guard: a narrow extractor's locations against a deliberately loose probe's, so a spelling
     * the extractor reads as nothing at all shows up as a difference instead of as silent agreement.
     */
    fun locationsMatching(vararg patterns: Regex): List<String> =
        sqlScripts
            .sortedBy { it.name }
            .flatMap { file ->
                schemaOnly(file)
                    .lineSequence()
                    .withIndex()
                    .filter { (_, line) -> patterns.any { it.containsMatchIn(line) } }
                    .map { (index, _) -> "${file.name}:${index + 1}" }
            }
}
