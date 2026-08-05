package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import java.time.Instant
import java.util.UUID

interface UserRepositoryInterface {
    fun findUserById(id: UUID): User?

    fun findUserByName(name: String): User?

    fun findUserByIdIncludingDeleted(id: UUID): User?

    fun saveUser(user: User): User

    /**
     * Tombstone an account, recording [at] as its softDeletedAt. An account that is already
     * tombstoned keeps the instant it was given first: a repeated deletion request must not push
     * its retention deadline further away.
     */
    fun markPendingDeletion(user: User, at: Instant)

    fun permanentlyDeleteUser(user: User)

    /**
     * Returns the tombstoned users whose softDeletedAt is strictly before [cutoff], which the
     * regular lookups hide. Used by the tombstone sweep to find accounts whose delete task is no
     * longer in flight.
     */
    fun findTombstonedUsersSoftDeletedBefore(cutoff: Instant): List<User>
}
