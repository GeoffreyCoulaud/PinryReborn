package fr.geoffreyCoulaud.pinryReborn.detekt

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/**
 * The simple name an expression ends on, empty when it ends on something that has no name.
 *
 * These rules read shapes rather than resolved types, so this is what stands in for "which member
 * is this": `Instant.now()` ends on `now`, `query.softDeletedAt` ends on `softDeletedAt`, and a
 * literal, a lambda or `this` ends on nothing. Empty rather than null so a caller compares one
 * value instead of branching on absence first.
 *
 * Navigation is taken at [KtQualifiedExpression], which covers `a.b` and `a?.b` alike: a safe call
 * names the same member as a dotted one, and a rule that reads names must not tell them apart.
 */
internal fun KtExpression?.endsOnName(): String =
    when (this) {
        is KtQualifiedExpression -> selectorExpression.endsOnName()
        is KtCallExpression -> calleeExpression.endsOnName()
        is KtNameReferenceExpression -> getReferencedName()
        else -> ""
    }
