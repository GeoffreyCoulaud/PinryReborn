package fr.geoffreyCoulaud.pinryReborn.api.application.wiring

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.AuthConfig
import fr.geoffreyCoulaud.pinryReborn.api.usecases.AuthenticationAttemptLimiter
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PasswordChanger
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SessionRevoker
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * Constructs [PasswordChanger] with its configured minimum interval. The use case takes a raw
 * `Duration` (the `UserDataExportRequester` precedent) rather than the `AuthConfig` interface, so
 * `api-usecases` stays free of configuration; the composition root is the single place that reads it.
 */
@ApplicationScoped
class PasswordChangerProducer {
    @Produces
    @ApplicationScoped
    @Suppress("LongParameterList") // CDI producer: every parameter is a collaborator provided by the container.
    fun passwordChanger(
        userPasswordRepository: UserPasswordHashRepositoryInterface,
        passwordHasher: PasswordHasher,
        sessionRevoker: SessionRevoker,
        transactionRunner: TransactionRunner,
        clock: Clock,
        attemptLimiter: AuthenticationAttemptLimiter,
        config: AuthConfig,
    ): PasswordChanger =
        PasswordChanger(
            userPasswordRepository,
            passwordHasher,
            sessionRevoker,
            transactionRunner,
            clock,
            attemptLimiter,
            minimumInterval = config.passwordChangeMinimumInterval(),
        )
}
