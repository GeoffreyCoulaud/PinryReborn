package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.IssuedSession
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.SessionExpiryPolicy
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.TokenGenerator
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.UUID.randomUUID

@ApplicationScoped
class SessionRenewer(
    private val sessionTokenRepository: SessionTokenRepositoryInterface,
    private val tokenGenerator: TokenGenerator,
    private val clock: Clock,
    private val expiryPolicy: SessionExpiryPolicy,
) {
    /** Atomic rotation: save the new token, then delete the old, in one transaction. */
    @Transactional
    fun renew(current: SessionToken): IssuedSession {
        val token = tokenGenerator.generateToken()
        val expiresAt = expiryPolicy.expiryFrom(clock.now(), current.persistent)
        sessionTokenRepository.saveSessionToken(
            sessionToken = SessionToken(
                id = randomUUID(),
                user = current.user,
                expiresAt = expiresAt,
                persistent = current.persistent,
            ),
            tokenHash = TokenHasher.sha256(token),
        )
        sessionTokenRepository.deleteById(current.id)
        return IssuedSession(token, expiresAt, expiryPolicy.renewAfterFor(expiresAt, current.persistent))
    }
}
