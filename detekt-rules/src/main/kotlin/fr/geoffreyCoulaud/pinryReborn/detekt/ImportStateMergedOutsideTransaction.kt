package fr.geoffreyCoulaud.pinryReborn.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * A state transition is decided on the row as it is, inside the transaction that writes it.
 *
 * `Persistor.merge` writes every column and no model carries a version, so saving a copy of a row read
 * earlier restores that row's whole state, including whatever another actor committed in between. The
 * import row is written from two directions at once, a request and a worker, and this lot found seven
 * sites of that one defect before making the read and the write a single fenced pair.
 *
 * ## Reach
 *
 * The subject is **every copy merged**, not only one that names `state`: the merge writes all of it, so
 * the state comes back whether the copy named it or not. The chunk receiver proved it, restoring an
 * `AWAITING_ARCHIVE` over a cancellation through a write of two counters. The scope is set in
 * `detekt.yml`, by path, over the import use cases; every other entity is exposed the same way and has
 * no fence, which is a backlog item rather than this rule's business.
 *
 * Four boundaries follow, all deliberate. The copy is read where it is written, so one hidden behind a
 * named helper (`save(cancelled(row))`) is not seen; the transaction is recognised by the name
 * `inTransaction` alone, this project's only transaction boundary; and both are spellings rather than
 * resolved types, since this rule set runs without type resolution.
 *
 * The fourth is the one to know. The rule sees where the write is, not where the read was, so a row
 * read outside and saved inside a transaction passes it: opening a transaction around a copy taken
 * before it changes nothing about what the merge restores. That half is a behaviour, and it is held by
 * behaviour tests, whose transaction fake answers a read taken outside one with a cancelled row
 * (`UserDataImportRunnerTest`, `UserDataImportCancellerTest`). This rule is the other half: it fails
 * the build on a writer that never opened a transaction at all, which is the shape every one of the
 * seven sites actually took.
 */
class ImportStateMergedOutsideTransaction(
    config: Config,
) : Rule(
        config,
        "A copy of a row saved outside the transaction that read it restores whatever another actor " +
            "wrote in between, its state included.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!expression.mergesARowCopy() || expression.insideATransaction()) return
        report(
            Finding(
                Entity.from(expression),
                "This save merges a copy of a row read elsewhere, which restores every column that " +
                    "copy carried, its state included. Write it through saveFenced, which reads the " +
                    "row inside the transaction that saves it.",
            ),
        )
    }

    /** A `save` handed nothing but a copy of a row: the shape every site of this defect took. */
    private fun KtCallExpression.mergesARowCopy(): Boolean {
        val argument = valueArguments.singleOrNull()?.getArgumentExpression()
        return calleeExpression.endsOnName() == SAVE && argument.asRowCopy() != null
    }

    /** The `copy` the argument ends on, dotted or bare; anything else the row was not copied from. */
    private fun KtExpression?.asRowCopy(): KtCallExpression? =
        when (this) {
            is KtQualifiedExpression -> selectorExpression.asRowCopy()
            is KtCallExpression -> takeIf { calleeExpression.endsOnName() == COPY }
            else -> null
        }

    /** Lexical, which is what the fence is: the read and the write are one pair or they are not. */
    private fun KtElement.insideATransaction(): Boolean =
        parents.filterIsInstance<KtCallExpression>().any { it.calleeExpression.endsOnName() == TRANSACTION }

    private companion object {
        private const val SAVE = "save"
        private const val COPY = "copy"

        /** `TransactionRunner`'s single member, and this project's only transaction boundary. */
        private const val TRANSACTION = "inTransaction"
    }
}
