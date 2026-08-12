package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A unique constraint is not complete until someone has written what a client sees when it fires: every one
 * the migrations declare appears in [namedOutcomes] with that answer, "no translation, deliberately" included
 * (`docs/adr/0009-unique-index-named-outcomes.md`, decision 1).
 *
 * It enforces that an outcome is named, not that it is true: a wrong entry passes.
 */
class UniqueConstraintOutcomeTest {
    private val namedOutcomes =
        mapOf(
            "ix_users_name_nocase" to
                "UserRepository.saveUser translates it to UsernameAlreadyTakenException and UserCreator " +
                "rethrows UsernameAlreadyTakenError, so the client sees 409 USERNAME_ALREADY_EXISTS.",
            "ux_tasks_dedup" to
                "No error while a live task exists: EbeanTaskQueue.enqueueDeduplicated catches it and returns the " +
                "live task the dedup key already names, which is the convergence TaskQueueInterface documents. " +
                "A violation with no live task behind it has nothing to converge on and propagates, so the " +
                "client sees 500.",
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

    // Uniqueness has two spellings: a standalone `create unique index`, and an inline constraint at table creation.
    private val uniqueIndexStatement =
        Regex("""create\s+unique\s+index\s+(\w+)""", RegexOption.IGNORE_CASE)

    private val inlineUniqueConstraint =
        Regex("""constraint\s+(\w+)\s+unique\b""", RegexOption.IGNORE_CASE)

    // The loose probe for the two extractors above, in the sense MigrationDirectory.locationsMatching describes.
    private val looseUniqueness = Regex("""\bunique\b""", RegexOption.IGNORE_CASE)

    // No non-empty guard, unlike DbMigrationModelCoverageTest: the table is not empty, so an empty extraction fails.
    private val declaredConstraints: Set<String> =
        MigrationDirectory
            .sqlScripts
            .flatMap { file ->
                val text = MigrationDirectory.schemaOnly(file)
                (uniqueIndexStatement.findAll(text) + inlineUniqueConstraint.findAll(text))
                    .map { it.groupValues[1] }
                    .toList()
            }.toSet()

    @Test
    fun `Given the migration scripts, Then every unique constraint they declare names its outcome`() {
        // Sorted so a failure reads the same twice; set equality, so a stale entry fails as loudly as a new one.
        assertEquals(namedOutcomes.keys.sorted(), declaredConstraints.sorted())
    }

    @Test
    fun `Given the outcome table, Then no entry names a constraint without answering for it`() {
        // The assertion above reads keys only, so `"ux_new" to ""` satisfies it while the silence stays.

        // Given
        val unanswered = namedOutcomes.filterValues { it.isBlank() }.keys.sorted()

        // Then
        assertEquals(emptyList<String>(), unanswered)
    }

    @Test
    fun `Given the migration scripts, Then the extraction reads every line that declares uniqueness`() {
        // Guards against the spelling it does not know: a form missing from both sides makes them agree in silence.

        // Given
        val extracted = MigrationDirectory.locationsMatching(uniqueIndexStatement, inlineUniqueConstraint)

        // Then
        assertEquals(MigrationDirectory.locationsMatching(looseUniqueness), extracted)
    }
}
