package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import java.time.Instant
import java.util.UUID

interface UserRepositoryInterface {
    fun findUserById(id: UUID): User?

    fun findUserByName(name: String): User?

    fun findUserByNameIncludingDeleted(name: String): User?

    fun findUserByIdIncludingDeleted(id: UUID): User?

    fun saveUser(user: User): User

    fun markPendingDeletion(user: User)

    fun permanentlyDeleteUser(user: User)

    /**
     * Returns soft-deleted users (`deleted = true`) whose `whenModified` is strictly before
     * [cutoff], including the soft-deleted rows the regular queries filter out. Used by the
     * tombstone sweep to find accounts whose delete task is no longer in flight.
     */
    fun findTombstonedUsersModifiedBefore(cutoff: Instant): List<User>
}
