package fr.geoffreyCoulaud.pinryReborn.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * Past a few lines a comment has become documentation, which belongs where documentation lives: a spec,
 * an ADR, the backlog or a handoff (`agents/writing.md`). The threshold is deliberately generous, since
 * no rule can judge the reason that justifies a long comment; it catches the prose that has clearly
 * moved in, not the explanation that earns its second line.
 */
class CommentCarriesDocumentation(
    config: Config,
) : Rule(
        config,
        "A comment past a few lines is documentation, and documentation belongs in a document.",
    ) {
    private val allowedLines: Int by config(DEFAULT_ALLOWED_LINES)

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        // Consecutive line comments read as one comment, so they are measured as one. A blank line or
        // any code between them ends the run, which is what `nextLeafOfRun` walks.
        val visited = mutableSetOf<PsiComment>()
        file.collectComments().forEach { comment ->
            if (comment in visited) return@forEach
            val run = runFrom(comment)
            visited.addAll(run)
            val lines = run.sumOf { it.text.count { character -> character == '\n' } + 1 }
            if (lines <= allowedLines) return@forEach
            // Reported at the comment, signed by the enclosing function, class or file. detekt shortens a
            // signature only for those three (`Signatures.searchSignature`) and falls back to raw text for
            // anything else, which would put the comment, or a whole property, inside the baseline id.
            val start = run.first()
            report(
                Finding(
                    Entity.from(start, start.signatureAnchor() ?: file),
                    "This comment is $lines lines long, past the $allowedLines allowed. " +
                        "Keep the sentence that says why, and move the rest to the document that owns it.",
                ),
            )
        }
    }

    private fun PsiElement.signatureAnchor(): PsiElement? =
        parents.firstOrNull { it is KtNamedFunction || it is KtClassOrObject }

    // Collected by descent rather than by `visitComment`: a KDoc is a PsiComment but accepts the Kotlin
    // visitor, which routes it to visitElement and never to visitComment.
    private fun KtFile.collectComments(): List<PsiComment> = descendants().filterIsInstance<PsiComment>().toList()

    // Walked child by child rather than through `children`, which skips leaf elements: a line comment
    // and a block comment are leaves, and only a KDoc is composite.
    private fun PsiElement.descendants(): Sequence<PsiElement> =
        sequence {
            var child = firstChild
            while (child != null) {
                yield(child)
                yieldAll(child.descendants())
                child = child.nextSibling
            }
        }

    /** [first] plus every line comment that follows it with nothing but same-line whitespace between. */
    private fun runFrom(first: PsiComment): List<PsiComment> {
        val run = mutableListOf(first)
        var current: PsiElement = first
        while (true) {
            val next = nextLeafOfRun(current) ?: return run
            run.add(next)
            current = next
        }
    }

    private fun nextLeafOfRun(from: PsiElement): PsiComment? {
        var sibling = from.nextSibling
        while (sibling is PsiWhiteSpace) {
            if (sibling.text.count { it == '\n' } > 1) return null
            sibling = sibling.nextSibling
        }
        return sibling as? PsiComment
    }

    private companion object {
        /** Four lines: past that, every comment this project measured had become documentation. */
        private const val DEFAULT_ALLOWED_LINES = 4
    }
}
