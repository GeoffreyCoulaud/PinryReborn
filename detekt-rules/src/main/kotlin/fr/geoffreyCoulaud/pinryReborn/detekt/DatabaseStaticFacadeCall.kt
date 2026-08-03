package fr.geoffreyCoulaud.pinryReborn.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression

/**
 * The default `Database` is reached through two static facades, `io.ebean.DB` and the deprecated
 * `io.ebean.Ebean`, and a read through either needs no `Database` instance at all. That is the one
 * shape the assertion confining the injected `Database` cannot see, so a production class can write
 * `io.ebean.DB.find(BoardModel::class.java, id)` and read a recyclable row unfiltered.
 *
 * This rule catches the fully-qualified form `io.ebean.DB.find(...)`, which carries no import and so
 * escapes the D5 Konsist import ban the way `QueryBeanConstructedByQualifiedName` documents for query
 * beans. The imported form `DB.find(...)` needs an `import io.ebean.DB`, which D5 bars, so it never
 * reaches this rule. Tests are exempt by path in `detekt.yml`: `RepositoryTest` calls `DB.getDefault()`.
 */
class DatabaseStaticFacadeCall(
    config: Config,
) : Rule(
        config,
        "A call on the io.ebean.DB or io.ebean.Ebean static facade reads the default Database " +
            "without the injected instance, so it skips the soft-delete filter.",
    ) {
    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val facade = expression.receiverExpression.text
        if (facade !in STATIC_FACADE_FQN) return
        report(
            Finding(
                Entity.from(expression),
                "$facade is a static facade over the default Database. " +
                    "Inject the Database port instead, so the read stays filtered.",
            ),
        )
    }

    private companion object {
        /** The two static facades over the default Database, by the fully-qualified receiver text. */
        private val STATIC_FACADE_FQN = setOf("io.ebean.DB", "io.ebean.Ebean")
    }
}
