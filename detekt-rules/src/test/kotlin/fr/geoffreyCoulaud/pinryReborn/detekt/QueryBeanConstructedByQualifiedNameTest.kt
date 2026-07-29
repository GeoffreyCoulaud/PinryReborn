package fr.geoffreyCoulaud.pinryReborn.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryBeanConstructedByQualifiedNameTest {
    private val rule = QueryBeanConstructedByQualifiedName(Config.empty)

    @Test
    fun `Given a query bean constructed by its qualified name, Then it is reported`() {
        // Given: the one construction shape an import assertion cannot see
        val code =
            """
            class Repository {
                fun find() =
                    fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query
                        .QPinModel()
                        .findList()
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then: the whole qualified construction is reported, and the message names the bean, so a
        // rule reporting the `findList()` call that wraps it fails here
        val finding = findings.single()
        assertEquals(3, finding.entity.location.source.line)
        assertEquals(
            "QPinModel is constructed by qualified name, which no import assertion can see. " +
                "Import it, or go through the queries package.",
            finding.message,
        )
    }

    @Test
    fun `Given a query bean constructed by its imported name, Then nothing is reported`() {
        // Given: the ordinary shape, which the Konsist import assertion covers instead
        val code =
            """
            import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QPinModel

            class Repository {
                fun find() = QPinModel().findList()
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given a qualified call that constructs no query bean, Then nothing is reported`() {
        // Given
        val code =
            """
            class Repository {
                fun find() = java.util.UUID.randomUUID()
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given a qualified call on a type merely named like a query bean, Then nothing is reported`() {
        // Given: the shape is `Q<Something>Model`, so a class that only starts with Q is not one
        val code =
            """
            class Repository {
                fun find() = com.example.QueueModel()
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }
}
