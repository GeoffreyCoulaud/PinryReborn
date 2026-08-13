package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.AuthConfig
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Pins the attempt-limiting policy the application ships with, and the assumption under it: a
 * comma-separated string maps to `List<Duration>` (`docs/specs/2026-08-13-auth-attempt-limiting.md`).
 * The values come from `application.properties`, which restates each one and so shadows `@WithDefault`.
 */
@QuarkusTest
class AuthConfigDefaultsIntegrationTest {
    @Inject
    lateinit var config: AuthConfig

    @Test
    fun `Given no operator override, Then the attempt-limiting policy is the specified one`() {
        assertEquals(5, config.attemptLimitThreshold())
        assertEquals(
            listOf(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10)),
            config.attemptLimitBackoff(),
        )
        assertEquals(Duration.ofMinutes(15), config.attemptLimitForgetAfter())
        assertEquals(10000, config.attemptLimitMaxTrackedKeys())
    }
}
