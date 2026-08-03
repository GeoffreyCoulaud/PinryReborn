package fr.geoffreyCoulaud.pinryReborn.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/**
 * The default `Database` is reached through two static facades, `io.ebean.DB` and the deprecated
 * `io.ebean.Ebean`, and a read through either needs no `Database` instance at all. That is the one
 * shape the assertion confining the injected `Database` cannot see, so a production class can write
 * `io.ebean.DB.find(BoardModel::class.java, id)` and read a recyclable row unfiltered.
 *
 * The rule reports both spellings of each call: the imported form `DB.find(...)`, where the receiver
 * is a bare name the file imports as `io.ebean.DB`/`io.ebean.Ebean`, and the fully-qualified form
 * `io.ebean.DB.find(...)`, which carries no import and escapes a Konsist import ban the same way
 * `QueryBeanConstructedByQualifiedName` documents. The import is checked for the bare-name form so
 * a project-local `DB` that the file does not import is left alone.
 *
 * Tests are exempt by path in `detekt.yml`: `RepositoryTest` legitimately calls `DB.getDefault()`.
 */
class DatabaseStaticFacadeCall(
    config: Config,
) : Rule(
        config,
        "A call on the io.ebean.DB or io.ebean.Ebean static facade reads the default Database " +
            "without the injected instance, so it skips the soft-delete filter.",
    ) {
    override fun visitQualifiedExpression(expression: KtQualifiedExpression) {
        super.visitQualifiedExpression(expression)
        val receiverName = expression.receiverExpression.endsOnName()
        val facadeFqn = FACADE_FQN_BY_NAME[receiverName] ?: return
        val containingFile = expression.containingFile as? KtFile
        val imported = containingFile
            ?.importDirectives
            ?.any { it.importedFqName?.asString() == facadeFqn }
            ?: false
        if (!imported) return
        report(
            Finding(
                Entity.from(expression),
                "$facadeFqn is a static facade over the default Database. " +
                    "Inject the Database port instead, so the read stays filtered.",
            ),
        )
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        if (expression.receiverExpression.text !in STATIC_FACADE_FQN) return
        report(
            Finding(
                Entity.from(expression),
                "${expression.receiverExpression.text} is a static facade over the default Database. " +
                    "Inject the Database port instead, so the read stays filtered.",
            ),
        )
    }

    private companion object {
        /** The two static facades over the default Database, by the simple name a call receiver takes. */
        private val FACADE_FQN_BY_NAME: Map<String, String> =
            mapOf(
                "DB" to "io.ebean.DB",
                "Ebean" to "io.ebean.Ebean",
            )

        /** The same two facades, by the fully-qualified text a dot-qualified receiver carries. */
        private val STATIC_FACADE_FQN = setOf("io.ebean.DB", "io.ebean.Ebean")
    }
}
