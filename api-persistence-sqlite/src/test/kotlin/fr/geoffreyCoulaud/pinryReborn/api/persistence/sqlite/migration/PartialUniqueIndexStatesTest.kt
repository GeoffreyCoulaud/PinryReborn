package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.migration

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.PartialUniqueIndexStates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A partial unique index constrains the rows its `where` clause selects, and the query that means those rows
 * repeats the same states in Kotlin. Each set is named once in [PartialUniqueIndexStates]; this pins it to the
 * predicate of the migration that created the index, so a narrowing or a widening of either side fails here
 * rather than leaving the two disagreeing (spec `docs/specs/2026-08-12-p2-debt-triage.md:142-155`).
 *
 * Unique indexes only: a partial index that is not unique is a plan hint, so a query naming other states than
 * its predicate is slower, while the same disagreement on a unique one is a wrong answer.
 *
 * It reads the literals the `state` comparison names, not what the predicate means. A `not in`, a literal
 * compared against another column, and a dropped conjunct (`dedup_key is not null`) all pass with the set
 * unchanged. A predicate naming its states any other way reads as none at all, which fails.
 */
class PartialUniqueIndexStatesTest {
    private val namedStates =
        mapOf(
            "ux_tasks_dedup" to PartialUniqueIndexStates.liveTaskStates,
            "uq_user_data_exports_pending" to PartialUniqueIndexStates.pendingExportStates,
        )

    // Name and predicate in one pattern: `[^;]` cannot leave the statement, so the `where` found is its own.
    private val partialUniqueIndexStatement =
        Regex("""create\s+unique\s+index\s+(\w+)[^;]*?\bwhere\b([^;]*)""", RegexOption.IGNORE_CASE)

    // The loose probe for the extractor above, in the sense MigrationDirectory.locationsMatching describes.
    private val looseConditionalUniqueness = Regex("""\bunique\b.*\bwhere\b""", RegexOption.IGNORE_CASE)

    private val stateComparison =
        Regex("""\bstate\b\s*(?:=|\bin\b)\s*(\([^)]*\)|'[^']*')""", RegexOption.IGNORE_CASE)

    private val quotedLiteral = Regex("""'([^']*)'""")

    /**
     * The partial unique indexes whose predicate names a literal, in file order: one carrying none has no state
     * set to mirror. A list rather than a map, so an index a later migration recreates is not read as one.
     */
    private val stateBearingIndexes: List<PartialUniqueIndex> =
        MigrationDirectory
            .sqlScripts
            .sortedBy { it.name }
            .flatMap { file ->
                partialUniqueIndexStatement
                    .findAll(MigrationDirectory.schemaOnly(file))
                    .map { match ->
                        PartialUniqueIndex(
                            name = match.groupValues[1],
                            file = file.name,
                            predicate = match.groupValues[2],
                        )
                    }.toList()
            }.filter { quotedLiteral.containsMatchIn(it.predicate) }

    @Test
    fun `Given the migration scripts, Then every partial unique index has its state set named in Kotlin`() {
        // No non-empty guard, as in UniqueConstraintOutcomeTest: the table is not empty, so an empty
        // extraction fails.

        // Given
        val declared = stateBearingIndexes.map { it.name }.sorted()

        // Then
        assertEquals(namedStates.keys.sorted(), declared)
    }

    @Test
    fun `Given a named state set, Then it is the one its index selects`() {
        // Given
        val disagreeing =
            stateBearingIndexes
                .mapNotNull { index ->
                    val selected = statesSelectedBy(index.predicate)
                    if (selected == namedStates[index.name]) {
                        null
                    } else {
                        "${index.file} selects ${index.name} on ${sorted(selected)}, " +
                            "Kotlin names ${sorted(namedStates[index.name].orEmpty())}"
                    }
                }.sorted()

        // Then
        assertEquals(emptyList<String>(), disagreeing)
    }

    @Test
    fun `Given the migration scripts, Then the extraction reads every line declaring uniqueness under a condition`() {
        // Guards against the spelling it does not know: an index missing from both sides is a set nobody mirrors.

        // Given
        val extracted = MigrationDirectory.locationsMatching(partialUniqueIndexStatement)

        // Then
        assertEquals(MigrationDirectory.locationsMatching(looseConditionalUniqueness), extracted)
    }

    /** The literals [predicate] compares `state` against, whichever of the two spellings it uses. */
    private fun statesSelectedBy(predicate: String): Set<String> =
        stateComparison
            .findAll(predicate)
            .flatMap { comparison -> quotedLiteral.findAll(comparison.groupValues[1]) }
            .map { it.groupValues[1] }
            .toSet()

    private fun sorted(states: Set<String>): String = states.sorted().joinToString(prefix = "[", postfix = "]")

    private data class PartialUniqueIndex(
        val name: String,
        val file: String,
        val predicate: String,
    )
}
