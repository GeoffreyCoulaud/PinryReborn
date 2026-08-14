package fr.geoffreyCoulaud.pinryReborn.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

/**
 * The project's own rules, discovered through the service loader
 * (`META-INF/services/dev.detekt.api.RuleSetProvider`) and activated in `config/detekt/detekt.yml`
 * under this rule set's id.
 *
 * Each rule carries its own name: this overload keys the set on `Rule.ruleName`, which defaults to
 * the class name, so a name here cannot drift from the class it configures.
 */
class PinryRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId(ID)

    override fun instance() =
        RuleSet(
            ruleSetId,
            listOf(
                ::QueryBeanConstructedByQualifiedName,
                ::SoftDeleteStateFilteredOutsideQueries,
                ::WallClockRead,
                ::DatabaseStaticFacadeCall,
                ::CommentCarriesDocumentation,
                ::ImportStateMergedOutsideTransaction,
            ),
        )

    private companion object {
        private const val ID = "pinry-reborn"
    }
}
