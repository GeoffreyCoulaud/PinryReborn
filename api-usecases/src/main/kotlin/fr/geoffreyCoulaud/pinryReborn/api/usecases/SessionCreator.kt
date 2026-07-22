package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.IssuedSession
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Login.BasicAuthLogin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.SessionExpiryPolicy
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.TokenGenerator
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID.randomUUID

@ApplicationScoped
class SessionCreator(
    private val userAuthenticator: UserAuthenticator,
    private val sessionTokenRepository: SessionTokenRepositoryInterface,
    private val tokenGenerator: TokenGenerator,
    private val clock: Clock,
    private val expiryPolicy: SessionExpiryPolicy,
    private val transactionRunner: TransactionRunner,
) {
    fun create(name: String, password: String, persistent: Boolean): IssuedSession =
        transactionRunner.inTransaction {
            val user = userAuthenticator.authenticate(BasicAuthLogin(userName = name, password = password))
            val token = tokenGenerator.generateToken()
            val expiresAt = expiryPolicy.expiryFrom(clock.now(), persistent)
            sessionTokenRepository.saveSessionToken(
                sessionToken =
                    SessionToken(id = randomUUID(), user = user, expiresAt = expiresAt, persistent = persistent),
                tokenHash = TokenHasher.sha256(token),
            )
            IssuedSession(token, expiresAt, expiryPolicy.renewAfterFor(expiresAt, persistent))
        }
}
