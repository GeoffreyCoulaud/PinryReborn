package fr.geoffreyCoulaud.pinryReborn.api.worker

import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.ReapExpiredUserDataExports
import io.github.oshai.kotlinlogging.KotlinLogging
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.util.concurrent.TimeUnit

/**
 * Drives the export retention lifecycle: purges expired export archives and sweeps orphaned
 * staged files on application startup, keeps sweeping on a fixed delay so exports do not linger
 * past their retention window, and stops the scheduler on shutdown.
 */
@ApplicationScoped
class ExportRetentionLifecycle(
    private val reapExpiredUserDataExports: ReapExpiredUserDataExports,
    private val purgeScheduler: PeriodicScheduler,
    private val config: ExportsConfig,
) {
    fun onStart(
        @Observes ignored: StartupEvent,
    ) = start()

    fun onStop(
        @Observes ignored: ShutdownEvent,
    ) = stop()

    // safeReap, not reap: `discardOrphanedStagedFiles` walks the staging directory outside any row's
    // own isolation, so the sweep can still throw as a whole, and on startup that ends the boot.
    fun start() {
        safeReap()
        val purgeIntervalMs = config.purgeInterval().toMillis().coerceAtLeast(1)
        purgeScheduler.scheduleWithFixedDelay(
            { safeReap() },
            purgeIntervalMs,
            purgeIntervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * [report] is a seam: a log handler attached in a test of this class reads nothing, so the line
     * an operator reads cannot be pinned through the log itself.
     */
    @Suppress("TooGenericExceptionCaught")
    fun safeReap(report: (String) -> Unit = { logger.info { it } }) {
        try {
            val counts = reapExpiredUserDataExports.reap()
            report("export sweep: ${counts.failed} failed, ${counts.expired} expired, ${counts.reclaimed} reclaimed")
        } catch (e: Exception) {
            logger.error(e) { "export purge failed" }
        }
    }

    fun stop() {
        purgeScheduler.shutdown()
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}
