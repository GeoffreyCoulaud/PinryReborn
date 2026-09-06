package fr.geoffreyCoulaud.pinryReborn.api.worker

import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.ReapUserDataExports
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
    private val reapUserDataExports: ReapUserDataExports,
    private val purgeScheduler: PeriodicScheduler,
    private val config: ExportsConfig,
) {
    fun onStart(
        @Observes ignored: StartupEvent,
    ) = start()

    fun onStop(
        @Observes ignored: ShutdownEvent,
    ) = stop()

    // safeReap, not reap: `swept()` wraps the action on one row, so the three selections that feed it
    // and the task lookup pass 1 makes per pending row are outside every net, and a sweep that throws
    // as a whole ends the boot it started in.
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

    @Suppress("TooGenericExceptionCaught")
    fun safeReap() {
        try {
            val counts = reapUserDataExports.reap()
            logger.info {
                "export sweep: ${counts.failed} failed, ${counts.expired} expired, ${counts.reclaimed} reclaimed"
            }
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
