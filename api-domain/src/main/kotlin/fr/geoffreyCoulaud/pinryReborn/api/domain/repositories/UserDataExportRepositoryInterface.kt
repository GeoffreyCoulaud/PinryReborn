package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import java.time.Instant
import java.util.UUID

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

    fun findAllExportIdsForUser(userId: UUID): List<UUID>

    /**
     * Return the candidate ids that have no export row, i.e. the orphans the GC
     * sweep should reclaim. Backed by a primary-key `IN (...)` lookup, so the
     * call is bounded by the size of [candidates] (the orphan sweep chunks it).
     */
    fun findMissingExportIds(candidates: Collection<UUID>): Set<UUID>

    fun deleteAllForUser(userId: UUID)
}
