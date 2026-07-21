package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import java.util.UUID

interface SessionTokenRepositoryInterface {
    /** Persist a new session token, storing [tokenHash] as its lookup key. Returns the saved token. */
    fun saveSessionToken(sessionToken: SessionToken, tokenHash: String): SessionToken

    /** Find a session token by the hash of its plaintext, or null. */
    fun findByTokenHash(tokenHash: String): SessionToken?

    /** Delete a session token by id. No-op if absent. */
    fun deleteById(id: UUID)

    /** Delete every session token belonging to the given user. */
    fun deleteAllForUser(userId: UUID)
}
