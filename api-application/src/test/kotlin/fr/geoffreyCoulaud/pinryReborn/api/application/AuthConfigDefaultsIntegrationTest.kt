package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.AuthConfig
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Pins the attempt-limiting defaults, and with them the one assumption the design rests on: a
 * `List<Duration>` config-mapping property resolves its `@WithDefault` from a comma-separated
 * string. See `docs/specs/2026-08-13-auth-attempt-limiting.md` section 5.
 */
@QuarkusTest
class AuthConfigDefaultsIntegrationTest {
    @Inject
    lateinit var config: AuthConfig

    @Test
    fun `Given no configuration, Then the attempt-limiting defaults are the specified ones`() {
        assertEquals(5, config.attemptLimitThreshold())
        assertEquals(
            listOf(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10)),
            config.attemptLimitBackoff(),
        )
        assertEquals(Duration.ofMinutes(15), config.attemptLimitForgetAfter())
        assertEquals(10000, config.attemptLimitMaxTrackedKeys())
    }
}
