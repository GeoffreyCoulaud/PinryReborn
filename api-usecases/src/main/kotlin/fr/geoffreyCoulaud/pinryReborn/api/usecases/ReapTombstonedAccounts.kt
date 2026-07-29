package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration

/**
 * Reclaim soft-deleted account tombstones whose delete task is no longer in flight: re-drives
 * [AccountDeletionCleaner.deleteAccountData] on each, which finishes any partial delete and
 * hard-deletes the tombstone. The grace avoids re-driving an account whose delete task is still
 * running.
 *
 * Not `@ApplicationScoped`: [tombstoneGrace] is a primitive ARC cannot resolve, so the bean is
 * produced in wiring (`GarbageCollectionProducers`), mirroring `ExportProducers` for
 * `ReapExpiredUserDataExports`.
 *
 * The second logger in `api-usecases`: the cleaner's DB transaction can still throw (its disk half
 * is best-effort after Sequence 1), so each re-drive is isolated in its own try/catch and a failure
 * is logged at WARN rather than aborting the batch. One bad tombstone must not block the rest
 * (docs/adr/0003-periodic-gc-and-best-effort-cleanup.md, consequence of the periodic garbage
 * collection design).
 */
class ReapTombstonedAccounts(
    private val userRepository: UserRepositoryInterface,
    private val accountDeletionCleaner: AccountDeletionCleaner,
    private val clock: Clock,
    private val tombstoneGrace: Duration,
) {
    /**
     * Re-drive the cleaner on every tombstone older than [tombstoneGrace]. Returns the number of
     * tombstones identified as candidates, the same accounting [ReapOrphanedStorage] uses: a
     * per-item re-drive is best-effort, so a throw is logged and the next tombstone is still
     * processed.
     */
    // The cleaner's DB transaction can throw anything; item-level isolation is the point (class KDoc).
    @Suppress("TooGenericExceptionCaught")
    fun reap(): Int {
        val cutoff = clock.now().minus(tombstoneGrace)
        val tombstones = userRepository.findTombstonedUsersSoftDeletedBefore(cutoff)
        for (user in tombstones) {
            try {
                accountDeletionCleaner.deleteAccountData(user.id)
            } catch (e: Exception) {
                logger.warn(e) { "tombstone reap failed for user ${user.id}" }
            }
        }
        return tombstones.size
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}
