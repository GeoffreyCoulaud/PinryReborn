package fr.geoffreyCoulaud.pinryReborn.detekt

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * The wall clock is read in one place, the implementation of the `Clock` port.
 *
 * A hidden clock makes its caller untestable, and it bypasses the truncation that keeps a stamped
 * instant equal to itself across a save and a read. The exemption is structural rather than a list
 * of blessed files: a class that declares the port may read the clock, and that is what makes it
 * the port.
 *
 * Reading the syntax tree rather than the file text is what separates a call from a sentence about
 * one: the port's own KDoc contains `Instant.now()` and is not a violation.
 *
 * ## Reach
 *
 * Four reads are reported by default, each written on its receiver: `Instant.now()`,
 * `LocalDate.now()`, `LocalDateTime.now()` and `System.currentTimeMillis()`. That shape is the only
 * one seen, since the rule visits dot-qualified expressions and nothing else. Three boundaries
 * follow, all deliberate.
 *
 * A bare `now()`, reached through a static import, is not a dot-qualified expression and is never
 * visited. Telling it from a local method of the same name takes the declaration it resolves to,
 * and this rule set runs without type resolution, so covering that form would mean reporting every
 * `now()` written anywhere.
 *
 * The four names are the whole default list, and it is closed rather than exhaustive: it covers the
 * ways this project writes the read. `ZonedDateTime`, `OffsetDateTime`, `LocalTime`,
 * `System.nanoTime()`, `java.util.Date()` and `Calendar.getInstance()` reach the same clock by other
 * routes and go unreported until `wallClockReads` names them.
 *
 * The scope is production sources in every module but the one declaring this rule, which cannot
 * carry itself as a detekt plugin. Test sources are excluded in `detekt.yml`, an exclusion accepted
 * for now rather than for good.
 */
class WallClockRead(
    config: Config,
) : Rule(
        config,
        "Reading the wall clock outside the Clock port hides a dependency the caller cannot control.",
    ) {
    @Configuration("the reads to report, each written `Receiver.member`")
    private val wallClockReads: List<String> by config(DEFAULT_WALL_CLOCK_READS)

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val receiver = expression.receiverExpression.endsOnName()
        val read = "$receiver.${expression.selectorExpression.endsOnName()}"
        if (read !in wallClockReads) return
        if (expression.declaresTheClockPort()) return
        report(
            Finding(
                Entity.from(expression),
                "$receiver reads the wall clock. Take the instant from the Clock port instead.",
            ),
        )
    }

    private fun KtElement.declaresTheClockPort(): Boolean {
        val owner = getStrictParentOfType<KtClassOrObject>() ?: return false
        // The name segment, not the entry's text: the same port is named `Clock`, by its qualified
        // name, or followed by a `by` clause, and all three declare it. A supertype that is not a
        // named type, such as a function type, has no segment to offer and declares nothing.
        return owner.superTypeListEntries.any { it.typeAsUserType?.referencedName == CLOCK_PORT }
    }

    private companion object {
        private const val CLOCK_PORT = "Clock"

        private val DEFAULT_WALL_CLOCK_READS =
            listOf(
                "Instant.now",
                "LocalDate.now",
                "LocalDateTime.now",
                "System.currentTimeMillis",
            )
    }
}
