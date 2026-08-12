package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.migration

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.PartialUniqueIndexStates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A partial unique index constrains the rows its `where` clause selects, and the query that means those rows
 * repeats the same states in Kotlin. Each set is named once in [PartialUniqueIndexStates]; this pins it to the
 * predicate the current schema gives the index, so a narrowing or a widening of either side fails here rather
 * than leaving the two disagreeing (spec `docs/specs/2026-08-12-p2-debt-triage.md:142-155`).
 *
 * The history is append-only, so an index is whatever the last migration that touched it left: [currentIndexes]
 * replays every declaration and removal in version order, which is what keeps a drop-and-recreate pair from
 * reading as two live indexes with two predicates to satisfy at once.
 *
 * Unique indexes only: a partial index that is not unique is a plan hint, so a query naming other states than
 * its predicate is slower, while the same disagreement on a unique one is a wrong answer.
 *
 * What it does not check, deliberately:
 * - It reads the literals the `state` comparison names, not what the predicate means, so
 *   `not (state in ('PENDING','RUNNING'))` passes on the set whose complement it selects.
 * - Its reach is a predicate quoting a literal, so a partial index on `where soft_deleted_at is null` is
 *   extracted and then dropped by that filter, and demands no Kotlin set.
 * - Nothing stops a query from re-inlining the literals, since the comparison is between the named set and the
 *   DDL and never between the named set and what the query reads.
 * - That `findOne()` returns at most one row rests on the index's uniqueness columns, which it does not read.
 */
class PartialUniqueIndexStatesTest {
    private val namedStates =
        mapOf(
            "ux_tasks_dedup" to PartialUniqueIndexStates.liveTaskStates,
            "uq_user_data_exports_pending" to PartialUniqueIndexStates.pendingExportStates,
        )

    // Name and the rest of the statement in one pattern, matched over the whole file: `[^;]` cannot leave the
    // statement, so a statement split over several lines is read like one written on a single line.
    private val indexCreation =
        Regex("""create\s+(unique\s+)?index\s+(\w+)([^;]*)""", RegexOption.IGNORE_CASE)

    // SQLite's form (sqlite.org/lang_dropindex.html); no migration drops an index yet, so nothing pins it.
    private val indexRemoval =
        Regex("""drop\s+index\s+(?:if\s+exists\s+)?(\w+)""", RegexOption.IGNORE_CASE)

    // The loose probe for the extraction above, in the sense MigrationDirectory.locationsMatching describes,
    // counted over the same whole-file text rather than line by line.
    private val looseConditionalUniqueness =
        Regex("""\bunique\b[^;]*?\bwhere\b""", RegexOption.IGNORE_CASE)

    private val wherePredicate = Regex("""\bwhere\b""", RegexOption.IGNORE_CASE)

    private val stateComparison =
        Regex("""\bstate\b\s*(?:=|\bin\b)\s*(\([^)]*\)|'[^']*')""", RegexOption.IGNORE_CASE)

    private val quotedLiteral = Regex("""'([^']*)'""")

    private val versionNumber = Regex("""\d+""")

    /** The declaration each index name is left in by the last migration that touched it. */
    private val currentIndexes: Map<String, DeclaredIndex> =
        MigrationDirectory
            .sqlScripts
            .sortedBy { versionKeyOf(it) }
            .flatMap { eventsIn(it) }
            .fold(mutableMapOf()) { current, event ->
                val declared = event.declared
                if (declared == null) current.remove(event.name) else current[event.name] = declared
                current
            }

    /** Those of [currentIndexes] a Kotlin set has to mirror: one quoting no literal has no states to name. */
    private val stateBearingIndexes: List<DeclaredIndex> =
        currentIndexes
            .values
            .filter { conditionallyUnique(it) && quotedLiteral.containsMatchIn(it.predicate.orEmpty()) }
            .sortedBy { it.name }

    @Test
    fun `Given the current schema, Then every partial unique index has its state set named in Kotlin`() {
        // No non-empty guard, as in UniqueConstraintOutcomeTest: the table is not empty, so an empty
        // extraction fails.

        // Given
        val declared = stateBearingIndexes.map { it.name }

        // Then
        assertEquals(namedStates.keys.sorted(), declared)
    }

    @Test
    fun `Given a named state set, Then it is the one its index selects`() {
        // Given
        val disagreeing =
            stateBearingIndexes
                .mapNotNull { index ->
                    val selected = statesSelectedBy(index.predicate.orEmpty())
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
    fun `Given the migration scripts, Then the extraction reads every unique index under a condition`() {
        // Guards against the spelling it does not know: a statement missing from both sides is a set nobody mirrors.

        // Given
        val extracted =
            MigrationDirectory.sqlScripts.sumOf { file ->
                eventsIn(file).count { conditionallyUnique(it.declared) }
            }

        // Then
        assertEquals(looselyConditionalUniqueCount(), extracted)
    }

    /** What [file] does to the indexes, in the order it does it: a declaration, or a removal as a null one. */
    private fun eventsIn(file: File): List<IndexEvent> {
        val schema = MigrationDirectory.schemaOnly(file)
        val declarations =
            indexCreation.findAll(schema).map { match ->
                IndexEvent(
                    position = match.range.first,
                    name = match.groupValues[2],
                    declared =
                        DeclaredIndex(
                            name = match.groupValues[2],
                            file = file.name,
                            unique = match.groupValues[1].isNotBlank(),
                            predicate = wherePredicate.split(match.groupValues[3], limit = 2).getOrNull(1),
                        ),
                )
            }
        val removals =
            indexRemoval.findAll(schema).map { match ->
                IndexEvent(position = match.range.first, name = match.groupValues[1], declared = null)
            }
        return (declarations + removals).sortedBy { it.position }.toList()
    }

    /**
     * [file]'s version as a sortable key: the file name's own order puts `1.11` before `1.3`, and a
     * `<version>__dropsFor_<version>` name carries a second number that is not its own.
     */
    private fun versionKeyOf(file: File): String =
        versionNumber
            .findAll(file.name.removeSuffix(".sql").substringBefore("__"))
            .joinToString(".") { it.value.padStart(VERSION_NUMBER_WIDTH, '0') }

    private fun conditionallyUnique(index: DeclaredIndex?): Boolean =
        index != null && index.unique && index.predicate != null

    private fun looselyConditionalUniqueCount(): Int =
        MigrationDirectory.sqlScripts.sumOf { file ->
            looseConditionalUniqueness.findAll(MigrationDirectory.schemaOnly(file)).count()
        }

    /** The literals [predicate] compares `state` against, whichever of the two spellings it uses. */
    private fun statesSelectedBy(predicate: String): Set<String> =
        stateComparison
            .findAll(predicate)
            .flatMap { comparison -> quotedLiteral.findAll(comparison.groupValues[1]) }
            .map { it.groupValues[1] }
            .toSet()

    private fun sorted(states: Set<String>): String = states.sorted().joinToString(prefix = "[", postfix = "]")

    private data class DeclaredIndex(
        val name: String,
        val file: String,
        val unique: Boolean,
        val predicate: String?,
    )

    private data class IndexEvent(
        val position: Int,
        val name: String,
        val declared: DeclaredIndex?,
    )

    private companion object {
        /** Wide enough that a padded number never carries into the next one. */
        const val VERSION_NUMBER_WIDTH = 4
    }
}
