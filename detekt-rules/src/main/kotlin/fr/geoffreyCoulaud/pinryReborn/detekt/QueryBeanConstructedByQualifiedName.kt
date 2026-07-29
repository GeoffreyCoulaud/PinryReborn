package fr.geoffreyCoulaud.pinryReborn.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression

/**
 * Query beans are constructed by their imported name, never by their fully qualified one.
 *
 * The architecture test asserts over imports, so a construction written as
 * `…models.query.QPinModel()` needs no import and passes it unseen. That is the only shape it
 * misses, and it is an oddity for every query bean rather than for the recyclable ones alone, which
 * is why this rule needs no list of types to be right.
 */
class QueryBeanConstructedByQualifiedName(
    config: Config,
) : Rule(
        config,
        "A query bean constructed by its fully qualified name escapes the import assertion that " +
            "keeps query construction in one package.",
    ) {
    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val call = expression.selectorExpression as? KtCallExpression ?: return
        val constructed = call.calleeExpression.endsOnName()
        if (!QUERY_BEAN_NAME.matches(constructed)) return
        report(
            Finding(
                Entity.from(expression),
                "$constructed is constructed by qualified name, which no import assertion can see. " +
                    "Import it, or go through the queries package.",
            ),
        )
    }

    private companion object {
        /** The generator's own convention: `PinModel` yields `QPinModel`. */
        private val QUERY_BEAN_NAME = Regex("Q[A-Z][A-Za-z0-9]*Model")
    }
}
