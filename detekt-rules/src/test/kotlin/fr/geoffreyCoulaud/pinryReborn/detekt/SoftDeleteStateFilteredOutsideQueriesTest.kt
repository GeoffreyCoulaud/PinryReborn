package fr.geoffreyCoulaud.pinryReborn.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SoftDeleteStateFilteredOutsideQueriesTest {
    private val rule = SoftDeleteStateFilteredOutsideQueries(Config.empty)

    @Test
    fun `Given a query filtered on the active state, Then it is reported`() {
        // Given
        val code =
            """
            class Repository {
                fun find() = QPinModel().author.id.equalTo(id).softDeletedAt.isNull.findList()
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then: the navigation carrying the predicate is what is reported, and the message names
        // the continuation, so a rule reporting some other node on the same line fails here
        val finding = findings.single()
        assertEquals(2, finding.entity.location.source.line)
        assertEquals(
            "`softDeletedAt.isNull` states here what the queries package exists to state once. " +
                "Use active(), recycled() or any(), or an extension declared beside them.",
            finding.message,
        )
    }

    @Test
    fun `Given a query filtered on the recycled state, Then it is reported`() {
        // Given
        val code =
            """
            class Repository {
                fun find() = QPinModel().softDeletedAt.isNotNull.findList()
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(1, findings.size)
    }

    @Test
    fun `Given a comparison on the recycling instant, Then it is reported`() {
        // Given: the rule holds whatever predicate someone reaches for, not just the two spellings
        // the query beans make obvious
        val code =
            """
            class Repository {
                fun find(cutoff: Instant) = QUserModel().softDeletedAt.lessThan(cutoff).findList()
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(1, findings.size)
    }

    @Test
    fun `Given a predicate reached through this, Then it is reported`() {
        // Given: the navigation may start anywhere, including on an expression that has no name
        val code =
            """
            class Repository {
                fun find() = this.softDeletedAt.isNull
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(1, findings.size)
    }

    @Test
    fun `Given a predicate written as a safe call, Then it is reported`() {
        // Given: the property is `Instant?` on the model, so `?.` is the natural spelling of the
        // predicate outside the query DSL, and it is the same act as a dotted one
        val code =
            """
            class Repository {
                fun recycledBefore(model: PinModel, cutoff: Instant) =
                    model.softDeletedAt?.isBefore(cutoff)
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(1, findings.size)
    }

    @Test
    fun `Given an ordering on the recycling instant, Then nothing is reported`() {
        // Given: ordering is not filtering, and cursor pagination sorts on this column
        val code =
            """
            class SortStrategy {
                fun sortDown(query: QPinModel) = query.orderBy().softDeletedAt.desc().id.desc()

                fun sortUp(query: QPinModel) = query.orderBy().softDeletedAt.asc().id.asc()
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given a write of the recycling instant, Then nothing is reported`() {
        // Given: stamping the column is what the use case asked the adapter to do
        val code =
            """
            class Repository {
                fun softDelete(model: PinModel, at: Instant) {
                    model.softDeletedAt = at
                }
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given the recycling instant read as a value, Then nothing is reported`() {
        // Given: an accessor, a named argument and a plain read are not predicates
        val code =
            """
            class Repository {
                fun page(cursor: ModelCursor<PinModel>, query: QPinModel) =
                    filterDownFrom(query, { it.softDeletedAt }, cursor.pivot.softDeletedAt)

                fun toModel(pin: Pin) = PinModel(softDeletedAt = pin.softDeletedAt)
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }
}
