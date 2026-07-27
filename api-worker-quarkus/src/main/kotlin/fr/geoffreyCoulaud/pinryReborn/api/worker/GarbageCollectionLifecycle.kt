package fr.geoffreyCoulaud.pinryReborn.api.worker

import fr.geoffreyCoulaud.pinryReborn.api.usecases.ReapExpiredSessionTokens
import fr.geoffreyCoulaud.pinryReborn.api.usecases.ReapOrphanedStorage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.ReapTombstonedAccounts
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.ReapTerminalTasks
import io.github.oshai.kotlinlogging.KotlinLogging
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.util.concurrent.TimeUnit

/**
 * Drives the periodic garbage collection lifecycle: runs the four `Reap*` sweeps on application
 * startup, keeps sweeping on a fixed delay so inert rows and orphaned files do not accumulate, and
 * stops the executor on shutdown. Mirrors [ExportRetentionLifecycle]; the only structural
 * difference is four sweeps instead of one, each isolated in its own try/catch inside [safeAll] so
 * one throwing sweep is logged and does not stop the others (spec
 * docs/specs/2026-07-27-periodic-gc.md, D4). The executor is its own type ([GarbageCollectionExecutor]),
 * not a named `ScheduledExecutorService`, so the orphan disk scan and the tombstone re-drive do
 * heavy filesystem and DB work on a thread isolated from task claiming, the lease reaper, and
 * archive purging without relying on a string qualifier.
 */
@ApplicationScoped
class GarbageCollectionLifecycle(
    private val reapExpiredSessionTokens: ReapExpiredSessionTokens,
    private val reapOrphanedStorage: ReapOrphanedStorage,
    private val reapTombstonedAccounts: ReapTombstonedAccounts,
    private val reapTerminalTasks: ReapTerminalTasks,
    private val executor: GarbageCollectionExecutor,
    private val config: GarbageCollectionConfig,
) {
    fun onStart(
        @Observes ignored: StartupEvent,
    ) = start()

    fun onStop(
        @Observes ignored: ShutdownEvent,
    ) = stop()

    fun start() {
        safeAll()
        val intervalMs = config.interval().toMillis().coerceAtLeast(1)
        executor.scheduleWithFixedDelay(
            { safeAll() },
            intervalMs,
            intervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    // Each sweep can throw anything (DB, IO, parsing); sweep-level isolation is the point (D4).
    @Suppress("TooGenericExceptionCaught")
    fun safeAll() {
        try {
            reapExpiredSessionTokens.reap()
        } catch (e: Exception) {
            logger.error(e) { "session token sweep failed" }
        }
        try {
            reapOrphanedStorage.reap()
        } catch (e: Exception) {
            logger.error(e) { "orphaned storage sweep failed" }
        }
        try {
            reapTombstonedAccounts.reap()
        } catch (e: Exception) {
            logger.error(e) { "tombstone sweep failed" }
        }
        try {
            reapTerminalTasks.reap()
        } catch (e: Exception) {
            logger.error(e) { "terminal task sweep failed" }
        }
    }

    fun stop() {
        executor.shutdown()
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}
