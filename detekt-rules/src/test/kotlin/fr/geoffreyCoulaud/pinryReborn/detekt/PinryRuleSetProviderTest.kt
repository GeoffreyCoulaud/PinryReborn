package fr.geoffreyCoulaud.pinryReborn.detekt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readLines

class PinryRuleSetProviderTest {
    /**
     * Three of the six rules earn an activation red against the real sources, which is what proves
     * they are loaded. `QueryBeanConstructedByQualifiedName`, `DatabaseStaticFacadeCall` and
     * `ImportStateMergedOutsideTransaction` cannot: no production source constructs a query bean by
     * qualified name, calls `io.ebean.DB`, or merges an import state transition outside its
     * transaction unsuppressed, so each reports nothing whether it is registered or absent from the
     * rule set entirely, and detekt fails the build for neither. Their registration is asserted here
     * or nowhere.
     */
    @Test
    fun `Given the provider, Then its rule set carries exactly the six expected rules`() {
        // Given
        val provider = PinryRuleSetProvider()

        // When
        val ruleSet = provider.instance()

        // Then
        assertEquals("pinry-reborn", ruleSet.id.value)
        assertEquals(
            listOf(
                "CommentCarriesDocumentation",
                "DatabaseStaticFacadeCall",
                "ImportStateMergedOutsideTransaction",
                "QueryBeanConstructedByQualifiedName",
                "SoftDeleteStateFilteredOutsideQueries",
                "WallClockRead",
            ),
            ruleSet.rules.keys.map { it.value }.sorted(),
        )
    }

    /**
     * A `pinry-reborn` key nothing recognises costs a rule silently. detekt excludes custom rule
     * sets from configuration validation, and this project excludes the path a second time so the
     * module declaring the rules can be analysed at all (the `config:` block of `detekt.yml` says
     * why). Measured on this configuration: misspelling a key under `pinry-reborn` leaves the build
     * green, while the same typo under `style` fails it. Comparing the configured names to the
     * registered ones is what turns that silence into a failure.
     */
    @Test
    fun `Given the detekt configuration, Then it names exactly the rules the provider registers`() {
        // Given
        val ruleSet = PinryRuleSetProvider().instance()

        // When
        val configuredRules = ruleNamesConfiguredUnder(ruleSet.id.value)

        // Then
        assertEquals(ruleSet.rules.keys.map { it.value }.toSet(), configuredRules)
    }

    /**
     * A rule set block can name every rule correctly and run none of them. `active` is what decides,
     * detekt leaves a rule inactive when nothing sets it, and a custom rule set is unvalidated, so a
     * key misspelt or forgotten costs the rule in silence: the comparison above still passes, the
     * build still succeeds, and the rule stops reporting. Two of these three spent part of their
     * construction deliberately in that state, which is the state nothing else here tells apart from
     * a rule that runs.
     */
    @Test
    fun `Given the detekt configuration, Then it activates every rule the provider registers`() {
        // Given
        val ruleSet = PinryRuleSetProvider().instance()

        // When
        val activeRules = activeRuleNamesConfiguredUnder(ruleSet.id.value)

        // Then
        assertEquals(ruleSet.rules.keys.map { it.value }.toSet(), activeRules)
    }

    /** The rule names a rule set block declares in the project's detekt configuration. */
    private fun ruleNamesConfiguredUnder(ruleSetId: String): Set<String> =
        ruleEntriesConfiguredUnder(ruleSetId)
            .map { (name, _) -> name }
            .toSet()

    /** The rule names a rule set block declares **and** switches on. */
    private fun activeRuleNamesConfiguredUnder(ruleSetId: String): Set<String> =
        ruleEntriesConfiguredUnder(ruleSetId)
            .filter { (_, properties) -> properties.any { ACTIVE_TRUE.matches(it) } }
            .map { (name, _) -> name }
            .toSet()

    /**
     * Every rule a rule set block declares, each paired with the property lines it owns.
     *
     * A rule entry is a two-space-indented key alone on its line, and it owns the lines that follow
     * it until the next such entry: its properties are indented deeper and carry a value, and its
     * comments start on a `#`. That shape is enough to read the block without a YAML parser, which
     * the test classpath does not carry.
     */
    private fun ruleEntriesConfiguredUnder(ruleSetId: String): List<Pair<String, List<String>>> {
        val block = blockLinesOf(ruleSetId)
        val entryLines = block.indices.filter { RULE_ENTRY.matchEntire(block[it]) != null }
        return entryLines.mapIndexed { rank, start ->
            val name = checkNotNull(RULE_ENTRY.matchEntire(block[start])).groupValues[1]
            val end = entryLines.getOrElse(rank + 1) { block.size }
            name to block.subList(start + 1, end)
        }
    }

    /** The lines of a top-level block of the project's detekt configuration, header excluded. */
    private fun blockLinesOf(ruleSetId: String): List<String> {
        val configurationPath =
            checkNotNull(System.getProperty(CONFIGURATION_PATH_PROPERTY)) {
                "System property $CONFIGURATION_PATH_PROPERTY is unset: it must hold the absolute " +
                    "path of config/detekt/detekt.yml."
            }
        return Path
            .of(configurationPath)
            .readLines()
            .dropWhile { it != "$ruleSetId:" }
            .drop(1)
            .takeWhile { it.isBlank() || it.startsWith(" ") }
    }

    private companion object {
        /** Set by the Gradle test task, because the configuration lives outside this module. */
        private const val CONFIGURATION_PATH_PROPERTY = "pinryReborn.detektConfigurationPath"

        private val RULE_ENTRY = Regex(" {2}([A-Za-z][A-Za-z0-9]*):")

        /** A rule's own switch, at the property indentation, and set to nothing but `true`. */
        private val ACTIVE_TRUE = Regex(" {4}active: true\\s*")
    }
}
