package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import java.time.Instant
import java.util.UUID

// One method per question an export use case asks of the store, which trips the per-interface
// threshold. Suppressed rather than split, as UserDataExportRepository is for the same rule.
@Suppress("TooManyFunctions")
interface UserDataExportRepositoryInterface {
    fun save(export: UserDataExport): UserDataExport

    fun findById(id: UUID): UserDataExport?

    fun findAllForUser(
        userId: UUID,
        cursor: Cursor?,
        pageSize: Int,
    ): Page<UserDataExport>

    fun findPendingForUser(userId: UUID): UserDataExport?

    fun findReadyForUser(userId: UUID): UserDataExport?

    /** The most recent requestedAt across ALL states, DELETED and FAILED included. */
    fun findLastRequestedAtForUser(userId: UUID): Instant?

    fun findExpiredReadyExports(now: Instant): List<UserDataExport>

    /**
     * `PENDING` rows oldest first, bounded at the query: the sweep applies its grace after the
     * selection, so an unordered batch of recent rows would starve the oldest ones for good.
     */
    fun findPending(limit: Int): List<UserDataExport>

    /** Terminal rows that still name an archive, so the sweep reclaims each one exactly once. */
    fun findReclaimableTerminal(limit: Int): List<UserDataExport>

    fun findAllExportIdsForUser(userId: UUID): List<UUID>

    /**
     * Return the candidate ids that have no export row, i.e. the orphans the garbage collection
     * sweep should reclaim. Backed by a primary-key `IN (...)` lookup, so the
     * call is bounded by the size of [candidates] (the orphan sweep chunks it).
     */
    fun findMissingExportIds(candidates: Collection<UUID>): Set<UUID>

    fun deleteAllForUser(userId: UUID)
}
