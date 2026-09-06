package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportAlreadyInProgressException
import java.time.Instant
import java.util.UUID

/**
 * There is deliberately no `findActiveForUser` pre-insert check: ADR 0009 decision 2 bars a read that
 * exists only to answer uniqueness, and this import has no second refusal to order ahead of the first.
 */
interface UserDataImportRepositoryInterface {
    /**
     * Create or update an import row.
     * @throws ImportAlreadyInProgressException when the user already holds an active import.
     */
    fun save(userDataImport: UserDataImport): UserDataImport

    fun findById(id: UUID): UserDataImport?

    fun findAllForUser(
        userId: UUID,
        cursor: Cursor?,
        pageSize: Int,
    ): Page<UserDataImport>

    /**
     * `AWAITING_ARCHIVE` rows idle since before [instant] (the grace counts inactivity, not age), one
     * page by `id` after [afterId], `null` first, at most [limit] rows: a refused row is passed next page.
     */
    fun findAbandonableBefore(instant: Instant, afterId: UUID?, limit: Int): List<UserDataImport>

    /** Terminal rows that still hold archive bytes, one page by `id` after [afterId] as above. */
    fun findReclaimableTerminal(afterId: UUID?, limit: Int): List<UserDataImport>

    /**
     * `RUNNING` rows, one page by `id` after [afterId] as above, which the sweep pairs with their
     * task to tell a live attempt from a dead one.
     */
    fun findRunning(afterId: UUID?, limit: Int): List<UserDataImport>

    fun findAllImportIdsForUser(userId: UUID): List<UUID>

    /**
     * The candidate ids with no import row, i.e. the orphans the storage sweep reclaims. Backed by a
     * primary-key `IN (...)` lookup, so the call is bounded by the size of [candidates].
     */
    fun findMissingImportIds(candidates: Collection<UUID>): Set<UUID>

    fun deleteAllForUser(userId: UUID)
}
