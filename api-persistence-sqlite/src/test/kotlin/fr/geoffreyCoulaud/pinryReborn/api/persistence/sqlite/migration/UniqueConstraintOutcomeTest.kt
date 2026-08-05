package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A unique constraint is not complete until someone has written what a client sees when it fires.
 * Every constraint the committed migrations declare appears in [namedOutcomes] carrying that answer,
 * and "no translation, deliberately" is a valid answer: it is silence that is refused
 * (`docs/adr/0009-unique-index-named-outcomes.md`, decision 1).
 *
 * **Its limit, stated rather than discovered later: this enforces that an outcome is named, not that
 * it is true.** A wrong entry passes, and only the code and its own tests say otherwise. What this
 * stops is the constraint that arrives with no answer at all.
 */
class UniqueConstraintOutcomeTest {
    // The outcome is a value rather than a comment, so a new key cannot be added without writing one.
    private val namedOutcomes =
        mapOf(
            "ix_users_name_nocase" to
                "UserRepository.saveUser translates it to UsernameAlreadyTakenException and UserCreator " +
                "rethrows UsernameAlreadyTakenError, so the client sees 409 USERNAME_ALREADY_EXISTS.",
            "ux_tasks_dedup" to
                "No error: EbeanTaskQueue.enqueueWithin catches it and returns the live task the dedup key " +
                "already names, which is the convergence TaskQueueInterface documents.",
            "uq_images_pin_id" to
                "No translation, deliberately: EbeanImageRepository.saveWithin deletes by pinId then inserts, " +
                "in one transaction, so a second image for a pin replaces the first instead of colliding.",
            "uq_session_tokens_token_hash" to
                "No translation, deliberately: a collision means the secure token generator repeated itself, " +
                "which is a broken invariant rather than an applicative case, so 500 is the honest answer.",
            "uq_user_data_exports_pending" to
                "UserDataExportRepository translates it to ExportAlreadyInProgressException, so the client " +
                "sees 409 EXPORT_ALREADY_IN_PROGRESS.",
            "ix_user_password_hashes_user_created" to
                "UserPasswordHashRepository translates it to PasswordChangeCollisionException, so the client " +
                "sees 409 PASSWORD_CHANGE_COLLISION.",
        )

    private val migrationDirectory = File("src/main/resources/dbmigration")

    private val sqlScripts: List<File> =
        migrationDirectory
            .listFiles()
            ?.toList()
            .orEmpty()
            .filter { it.name.endsWith(".sql") }

    // The schema spells uniqueness two ways and enforces both: a standalone `create unique index`, and
    // the inline table constraint `@Column(unique = true)` produces, which SQLite accepts at creation.
    private val uniqueIndexStatement =
        Regex("""create\s+unique\s+index\s+(\w+)""", RegexOption.IGNORE_CASE)

    private val inlineUniqueConstraint =
        Regex("""constraint\s+(\w+)\s+unique\b""", RegexOption.IGNORE_CASE)

    // Deliberately loose, and only ever compared against the two extractors above: it over-matches so
    // that a third spelling, which they would read as no constraint at all, shows up as a difference.
    private val looseUniqueness = Regex("""\bunique\b""", RegexOption.IGNORE_CASE)

    // No non-empty guard sits beside these, unlike DbMigrationModelCoverageTest's: the table below is
    // not empty, so an extraction that stops matching fails the assertion rather than passing it.
    private val declaredConstraints: Set<String> =
        sqlScripts
            .flatMap { file ->
                val text = file.readText()
                (uniqueIndexStatement.findAll(text) + inlineUniqueConstraint.findAll(text))
                    .map { it.groupValues[1] }
                    .toList()
            }.toSet()

    @Test
    fun `Given the migration scripts, Then every unique constraint they declare names its outcome`() {
        // Sorted for a failure that reads the same twice; both sides are sets, so this is set equality
        // and a stale entry fails as loudly as a new constraint.
        assertEquals(namedOutcomes.keys.sorted(), declaredConstraints.sorted())
    }

    @Test
    fun `Given the migration scripts, Then the extraction reads every line that declares uniqueness`() {
        // Guards the assertion above against the spelling it does not know: an extractor blind to a
        // third form leaves that constraint out of both sides, which then agree in silence.

        // Given
        val extracted = locationsMatching(uniqueIndexStatement, inlineUniqueConstraint)

        // Then
        assertEquals(locationsMatching(looseUniqueness), extracted)
    }

    /** Where any of [patterns] matches, as `<file>:<line>` locators, sorted so a failure reads the same twice. */
    private fun locationsMatching(vararg patterns: Regex): List<String> =
        sqlScripts
            .sortedBy { it.name }
            .flatMap { file ->
                file
                    .readText()
                    .lineSequence()
                    .withIndex()
                    .filter { (_, line) -> patterns.any { it.containsMatchIn(line) } }
                    .map { (index, _) -> "${file.name}:${index + 1}" }
                    .toList()
            }
}
