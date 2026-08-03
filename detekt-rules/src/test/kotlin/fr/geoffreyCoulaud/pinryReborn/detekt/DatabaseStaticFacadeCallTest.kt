package fr.geoffreyCoulaud.pinryReborn.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DatabaseStaticFacadeCallTest {
    private val rule = DatabaseStaticFacadeCall(Config.empty)

    @Test
    fun `Given a fully-qualified call on io ebean DB, Then it is reported`() {
        // Given: the shape that hides the import, which the D5 Konsist import ban cannot see
        val code =
            """
            class Repository {
                fun find() = io.ebean.DB.find(Any::class.java)
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then: the whole facade call is reported, and the message names the facade
        val finding = findings.single()
        assertEquals(2, finding.entity.location.source.line)
        assertEquals(
            "io.ebean.DB is a static facade over the default Database. " +
                "Inject the Database port instead, so the read stays filtered.",
            finding.message,
        )
    }

    @Test
    fun `Given a fully-qualified call on io ebean Ebean, Then it is reported`() {
        // Given: the deprecated facade is the same hole
        val code =
            """
            class Repository {
                fun find() = io.ebean.Ebean.find(Any::class.java)
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        val finding = findings.single()
        assertEquals(2, finding.entity.location.source.line)
        assertEquals(
            "io.ebean.Ebean is a static facade over the default Database. " +
                "Inject the Database port instead, so the read stays filtered.",
            finding.message,
        )
    }

    @Test
    fun `Given an imported call on DB, Then this rule leaves it to the D5 import ban`() {
        // Given: the ordinary imported shape. D5 (Konsist) bars `import io.ebean.DB`, so this form
        // never reaches production; this rule deliberately does not double-handle it, which keeps the
        // rule free of the import-list branch that branch coverage could not reach from lint fixtures.
        val code =
            """
            import io.ebean.DB

            class Repository {
                fun find() = DB.find(Any::class.java)
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given a call on an injected Database, Then nothing is reported`() {
        // Given: the behaviour the rule exists to enforce, the port received by construction
        val code =
            """
            class Repository(private val database: Database) {
                fun find() = database.find(Any::class.java)
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given a call on a receiver merely named DB, Then nothing is reported`() {
        // Given: a project-local DB is not the facade; the rule matches the fully-qualified text only
        val code =
            """
            object DB {
                fun find() = 1
            }

            class Repository {
                fun go() = DB.find()
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given a fully-qualified call on another type, Then nothing is reported`() {
        // Given: the dot-qualified shape is common, and only the two facades are flagged
        val code =
            """
            class Repository {
                fun id() = java.util.UUID.randomUUID()
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }
}
