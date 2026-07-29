package fr.geoffreyCoulaud.pinryReborn.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/**
 * Whether a row is recycled is answered in one package, and nowhere else.
 *
 * The rule reports a shape rather than a spelling: anything that continues past `softDeletedAt`
 * except ordering. `isNull` and `isNotNull` are the two the query beans make obvious, but
 * `equalTo(null)`, `before(x)` and every other predicate are the same act and are caught the same
 * way. The dotted and the safe form are both navigation, and `?.` is the natural spelling wherever
 * the property keeps its nullable domain type. Writing the property, passing it as an argument and
 * reading it are not continuations and stay untouched.
 *
 * Its reach is set in `detekt.yml`, by path: the persistence module, minus the `queries` package
 * that owns the answer.
 */
class SoftDeleteStateFilteredOutsideQueries(
    config: Config,
) : Rule(
        config,
        "Filtering on the recycled state outside the queries package puts a business rule in a " +
            "place nobody reads it.",
    ) {
    override fun visitQualifiedExpression(expression: KtQualifiedExpression) {
        super.visitQualifiedExpression(expression)
        if (expression.receiverExpression.endsOnName() != RECYCLING_INSTANT) return
        val continuation = expression.selectorExpression.endsOnName()
        if (continuation in ORDERING) return
        report(
            Finding(
                Entity.from(expression),
                "`$RECYCLING_INSTANT.$continuation` states here what the queries package exists to " +
                    "state once. Use active(), recycled() or any(), or an extension declared beside them.",
            ),
        )
    }

    private companion object {
        /** The single member of the marker interface a recyclable model implements. */
        private const val RECYCLING_INSTANT = "softDeletedAt"

        /** Cursor pagination sorts on this column. Ordering is not filtering. */
        private val ORDERING = setOf("asc", "desc")
    }
}
