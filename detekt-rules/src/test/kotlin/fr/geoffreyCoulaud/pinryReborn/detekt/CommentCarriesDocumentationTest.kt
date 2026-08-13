package fr.geoffreyCoulaud.pinryReborn.detekt

import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommentCarriesDocumentationTest {
    private val rule = CommentCarriesDocumentation(Config.empty)

    @Test
    fun `Given a KDoc past the threshold, Then it is reported`() {
        // Given: five lines of prose on one declaration
        val code =
            """
            /**
             * One.
             * Two.
             * Three.
             * Four.
             */
            class Subject
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then: the message names the length and where the prose belongs
        val finding = findings.single()
        assertEquals(1, finding.entity.location.source.line)
        assertTrue(
            finding.message.startsWith("This comment is 6 lines long, past the 4 allowed."),
            "Unexpected message: ${finding.message}",
        )
    }

    @Test
    fun `Given a run of line comments past the threshold, Then it is reported once`() {
        // Given: consecutive line comments are one comment, not five
        val code =
            """
            // One.
            // Two.
            // Three.
            // Four.
            // Five.
            class Subject
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        val finding = findings.single()
        assertEquals(1, finding.entity.location.source.line)
        assertTrue(
            finding.message.startsWith("This comment is 5 lines long, past the 4 allowed."),
            "Unexpected message: ${finding.message}",
        )
    }

    @Test
    fun `Given a comment at the threshold, Then nothing is reported`() {
        // Given: four lines is the last allowed length
        val code =
            """
            // One.
            // Two.
            // Three.
            // Four.
            class Subject
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given two short runs separated by code, Then neither is reported`() {
        // Given: the blank line and the declaration both end a run, so lengths do not accumulate
        val code =
            """
            // One.
            // Two.
            class First

            // Three.
            // Four.
            class Second
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given a run interrupted by a blank line, Then the halves count separately`() {
        // Given: a blank line ends a run, so this is two comments of three lines and not one of six
        val code =
            """
            // One.
            // Two.
            // Three.

            // Four.
            // Five.
            // Six.
            class Subject
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given a block comment past the threshold, Then it is reported`() {
        // Given: the non-KDoc block form is the same prose by another syntax
        val code =
            """
            /*
             One.
             Two.
             Three.
             Four.
             */
            class Subject
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(1, findings.size)
    }

    @Test
    fun `Given a reported comment, Then its signature names the declaration and not the prose`() {
        // Given: a baseline entry is keyed on the signature, so prose inside it would expire on any edit
        val code =
            """
            /**
             * One.
             * Two.
             * Three.
             * Four.
             */
            class Subject
            """.trimIndent()

        // When
        val signature = rule.lint(code).single().entity.signature

        // Then
        assertTrue(signature.endsWith("Subject"), "Unexpected signature: $signature")
        assertTrue("One." !in signature, "The signature carries the comment text: $signature")
    }

    @Test
    fun `Given a comment inside a function body, Then its signature names the function and not the body`() {
        // Given: the enclosing element is a block, whose signature would be the whole body
        val code =
            """
            class Subject {
                fun work() {
                    // One.
                    // Two.
                    // Three.
                    // Four.
                    // Five.
                    val value = 1
                }
            }
            """.trimIndent()

        // When
        val signature = rule.lint(code).single().entity.signature

        // Then
        assertTrue(signature.endsWith("fun work"), "Unexpected signature: $signature")
        assertTrue("val value" !in signature, "The signature carries the function body: $signature")
    }

    @Test
    fun `Given a KDoc on a property, Then its signature names the enclosing object and not the property`() {
        // Given: detekt shortens a signature for a function, a class or a file, and falls back to raw
        // text otherwise, so a property would carry its own KDoc into the baseline id
        val code =
            """
            object Holder {
                /**
                 * One.
                 * Two.
                 * Three.
                 * Four.
                 */
                val value: Int = 1
            }
            """.trimIndent()

        // When
        val signature = rule.lint(code).single().entity.signature

        // Then
        assertTrue(signature.endsWith("Holder"), "Unexpected signature: $signature")
        assertTrue("One." !in signature, "The signature carries the comment text: $signature")
    }

    @Test
    fun `Given a comment outside any class or function, Then its signature names the file`() {
        // Given: a top-level comment has no function or class to anchor on
        val code =
            """
            // One.
            // Two.
            // Three.
            // Four.
            // Five.
            val topLevel = 1
            """.trimIndent()

        // When
        val signature = rule.lint(code).single().entity.signature

        // Then
        assertTrue(signature.endsWith(".kt"), "Unexpected signature: $signature")
    }

    @Test
    fun `Given a raised threshold, Then a comment below it is not reported`() {
        // Given: the threshold is configuration, so a module can hold itself to a different bar
        val config = TestConfig("allowedLines" to 6)
        val code =
            """
            // One.
            // Two.
            // Three.
            // Four.
            // Five.
            class Subject
            """.trimIndent()

        // When
        val findings = CommentCarriesDocumentation(config).lint(code)

        // Then
        assertEquals(0, findings.size)
    }
}
