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

    fun deleteAllForUser(userId: UUID)
}
