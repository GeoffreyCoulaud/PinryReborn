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
 * `Persistor.merge` writes every column and only `TaskModel` carries a version, so saving a row read
 * earlier restores that row's whole state, including whatever another actor committed in between. The
 * import row is written from two directions at once, a request and a worker, and this lot found nine
 * sites of that one defect before making the read and the write a single fenced pair.
 *
 * ## Reach
 *
 * The test is **inverted**, and this is the rule's whole shape: a save is reported unless its argument
 * is a construction. An insert is legal because a fresh row has no earlier state to restore; everything
 * else names a row that was read somewhere the transaction does not cover. Chasing the shapes that do
 * merge is what the first version tried, keying on a `copy` call written where the save is, and a named
 * local, a scoping function around the copy or one around the save all walked past it.
 *
 * A construction is told from a call by its name starting upper case, Kotlin's own convention, since
 * this rule set runs without type resolution. So does the rest: `save` and `inTransaction` are
 * spellings, not resolved members. The scope is set in `detekt.yml`, by path, over the import use
 * cases; every other entity is exposed the same way and has no fence, which is a backlog item rather
 * than this rule's business. **The message therefore names no helper**: `saveFenced` is the import
 * row's, and would be wrong advice the day the path filter widens.
 *
 * Two boundaries follow. A row built somewhere else and handed over through a property is reported all
 * the same (`imageRepository.save(created.image)` in `UserDataImportRunner`, suppressed inline with its
 * reason): the rule cannot see where that value came from, and the answer that keeps its reach is to
 * report and let the site say why.
 *
 * The second is the one to know. The rule sees where the write is, not where the read was, so a row
 * read outside and saved inside a transaction passes it: opening a transaction around a row taken
 * before it changes nothing about what the merge restores. That half is a behaviour, and it is held by
 * behaviour tests, whose transaction fake answers a read taken outside one with a cancelled row
 * (`UserDataImportRunnerTest`, `UserDataImportCancellerTest`). This rule is the other half: it fails
 * the build on a writer that never opened a transaction at all, which is the shape every one of the
 * nine sites took.
 *
 * None of them is left, so the rule holds no current violation open: every import-row write now goes
 * through `saveFenced` or `saveFencedOver`, and the completer's hand-over opens its own block. What it
 * guards is the next writer added to this package, which is why it stays after the fences landed.
 */
class ImportStateMergedOutsideTransaction(
    config: Config,
) : Rule(
        config,
        "A row saved outside the transaction that read it restores whatever another actor wrote in " +
            "between, its state included.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!expression.mergesARowReadElsewhere() || expression.insideATransaction()) return
        report(
            Finding(
                Entity.from(expression),
                "This save merges a row read elsewhere, which restores every column that row " +
                    "carried, its state included. Read the row inside the transaction that saves it.",
            ),
        )
    }

    /** A `save` handed one thing that is not a fresh row, which is every way of merging an old one. */
    private fun KtCallExpression.mergesARowReadElsewhere(): Boolean {
        val argument = valueArguments.singleOrNull() ?: return false
        return calleeExpression.endsOnName() == SAVE && !argument.getArgumentExpression().constructsARow()
    }

    /** Upper case is Kotlin's own mark of a constructor, and the only one available without types. */
    private fun KtExpression?.constructsARow(): Boolean =
        when (this) {
            is KtQualifiedExpression -> selectorExpression.constructsARow()
            is KtCallExpression -> CONSTRUCTION.containsMatchIn(calleeExpression.endsOnName())
            else -> false
        }

    /** Lexical, which is what the fence is: the read and the write are one pair or they are not. */
    private fun KtElement.insideATransaction(): Boolean =
        parents.filterIsInstance<KtCallExpression>().any { it.calleeExpression.endsOnName() == TRANSACTION }

    private companion object {
        private const val SAVE = "save"

        /** `TransactionRunner`'s single member, and this project's only transaction boundary. */
        private const val TRANSACTION = "inTransaction"

        /** A callee with no name matches nothing here, which is the reading that reports it. */
        private val CONSTRUCTION = Regex("^\\p{Lu}")
    }
}
