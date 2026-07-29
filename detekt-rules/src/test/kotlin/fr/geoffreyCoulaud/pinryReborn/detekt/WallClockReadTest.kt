package fr.geoffreyCoulaud.pinryReborn.detekt

import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WallClockReadTest {
    private val rule = WallClockRead(Config.empty)

    @Test
    fun `Given a call to Instant now, Then it is reported`() {
        // Given
        val code =
            """
            import java.time.Instant

            class Repository {
                fun stamp() = Instant.now()
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(1, findings.size)
    }

    @Test
    fun `Given the other three wall clock reads, Then each is reported`() {
        // Given
        val code =
            """
            import java.time.LocalDate
            import java.time.LocalDateTime

            class Repository {
                fun a() = LocalDate.now()

                fun b() = LocalDateTime.now()

                fun c() = System.currentTimeMillis()
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then: each read is reported where it is written and named by its own receiver, so a rule
        // reporting the enclosing function or the wrong one of the three fails here
        assertEquals(listOf(5, 7, 9), findings.map { it.entity.location.source.line })
        assertEquals(
            listOf(
                "LocalDate reads the wall clock. Take the instant from the Clock port instead.",
                "LocalDateTime reads the wall clock. Take the instant from the Clock port instead.",
                "System reads the wall clock. Take the instant from the Clock port instead.",
            ),
            findings.map { it.message },
        )
    }

    @Test
    fun `Given a wall clock read outside any class, Then it is reported`() {
        // Given: the exemption is carried by a class declaring the port, so code that belongs to no
        // class has nothing to claim it with
        val code =
            """
            import java.time.Instant

            fun stamp() = Instant.now()
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(1, findings.size)
    }

    @Test
    fun `Given a class implementing the Clock port, Then its wall clock read is not reported`() {
        // Given: the one place a wall clock may legitimately be read
        val code =
            """
            import java.time.Instant

            class SystemClock : Clock {
                override fun now(): Instant = Instant.now()
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given a class implementing the Clock port by its qualified name, Then its wall clock read is not reported`() {
        // Given: the supertype names the same port whether or not the file imported it
        val code =
            """
            import java.time.Instant

            class SystemClock : fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock {
                override fun now(): Instant = Instant.now()
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given a class delegating the Clock port, Then its wall clock read is not reported`() {
        // Given: a class that delegates the port still declares it, and the delegation clause is
        // part of the supertype entry rather than of the type it names
        val code =
            """
            import java.time.Instant

            class TruncatingClock(private val delegate: Clock) : Clock by delegate {
                fun startOfSecond(): Instant = Instant.now()
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given a class whose supertype is not a named type, Then its wall clock read is reported`() {
        // Given: a function type has no name to compare against the port, so it claims nothing
        val code =
            """
            import java.time.Instant

            class Ticker : () -> Instant {
                override fun invoke(): Instant = Instant.now()
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(1, findings.size)
    }

    @Test
    fun `Given a KDoc mentioning Instant now, Then nothing is reported`() {
        // Given: the false positive that matching file text produces and an AST rule does not
        val code =
            """
            class Repository {
                /**
                 * `Instant.now()` is nanosecond-resolution on Linux, so it is not used here.
                 */
                fun stamp(at: java.time.Instant) = at
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given a read outside the default list, Then it is not reported`() {
        // Given: the default list is closed, so another route to the same clock goes unseen
        val code =
            """
            import java.time.ZonedDateTime

            class Repository {
                fun stamp() = ZonedDateTime.now()
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given a configured list of reads, Then it replaces the default one`() {
        // Given: a project that reaches the clock another way says so, and stops paying for a
        // default it does not write
        val configuredRule = WallClockRead(TestConfig("wallClockReads" to listOf("ZonedDateTime.now")))
        val code =
            """
            import java.time.Instant
            import java.time.ZonedDateTime

            class Repository {
                fun a() = ZonedDateTime.now()

                fun b() = Instant.now()
            }
            """.trimIndent()

        // When
        val findings = configuredRule.lint(code)

        // Then
        assertEquals(
            listOf("ZonedDateTime reads the wall clock. Take the instant from the Clock port instead."),
            findings.map { it.message },
        )
    }

    @Test
    fun `Given a now call on an injected clock, Then nothing is reported`() {
        // Given: reading the clock through the port is the behaviour the rule exists to enforce
        val code =
            """
            class Repository(private val clock: Clock) {
                fun stamp() = clock.now()
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }
}
