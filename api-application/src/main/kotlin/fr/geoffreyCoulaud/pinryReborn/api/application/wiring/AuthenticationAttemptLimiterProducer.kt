package fr.geoffreyCoulaud.pinryReborn.api.application.wiring

import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.AuthConfig
import fr.geoffreyCoulaud.pinryReborn.api.usecases.AuthenticationAttemptLimiter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * Builds [AuthenticationAttemptLimiter] from its configured policy, on the [PasswordChangerProducer]
 * shape: raw values keep `api-usecases` free of configuration. Scoped once, since it holds counters.
 */
@ApplicationScoped
class AuthenticationAttemptLimiterProducer {
    @Produces
    @ApplicationScoped
    fun authenticationAttemptLimiter(clock: Clock, config: AuthConfig): AuthenticationAttemptLimiter =
        AuthenticationAttemptLimiter(
            clock = clock,
            threshold = config.attemptLimitThreshold(),
            backoffSteps = config.attemptLimitBackoff(),
            forgetAfter = config.attemptLimitForgetAfter(),
            maxTrackedKeys = config.attemptLimitMaxTrackedKeys(),
        )
}
