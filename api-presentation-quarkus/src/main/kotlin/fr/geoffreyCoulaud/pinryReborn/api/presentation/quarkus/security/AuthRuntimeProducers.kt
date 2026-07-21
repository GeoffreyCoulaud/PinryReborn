package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security

import fr.geoffreyCoulaud.pinryReborn.api.domain.security.SessionExpiryPolicy
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.AuthConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

@ApplicationScoped
class AuthRuntimeProducers {
    @Produces
    @ApplicationScoped
    fun sessionExpiryPolicy(config: AuthConfig): SessionExpiryPolicy =
        SessionExpiryPolicy(
            persistentTtl = config.persistentTtl(),
            ephemeralTtl = config.ephemeralTtl(),
            renewThreshold = config.renewThreshold(),
        )
}
