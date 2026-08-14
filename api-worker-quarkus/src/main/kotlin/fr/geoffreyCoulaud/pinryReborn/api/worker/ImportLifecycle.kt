package fr.geoffreyCoulaud.pinryReborn.api.worker

import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.ReapAbandonedUserDataImports
import io.github.oshai.kotlinlogging.KotlinLogging
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.util.concurrent.TimeUnit

/**
 * Drives the import sweep on startup and then on a fixed delay, as [ExportRetentionLifecycle] does its
 * own. Its [PeriodicScheduler] is `@Dependent`, so this sweep gets a thread of its own (ADR 0004).
 */
@ApplicationScoped
class ImportLifecycle(
    private val reapAbandonedUserDataImports: ReapAbandonedUserDataImports,
    private val sweepScheduler: PeriodicScheduler,
    private val config: ImportsConfig,
) {
    fun onStart(
        @Observes ignored: StartupEvent,
    ) = start()

    fun onStop(
        @Observes ignored: ShutdownEvent,
    ) = stop()

    fun start() {
        reapAbandonedUserDataImports.reap()
        val sweepIntervalMs = config.sweepInterval().toMillis().coerceAtLeast(1)
        sweepScheduler.scheduleWithFixedDelay(
            { safeReap() },
            sweepIntervalMs,
            sweepIntervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    fun safeReap() {
        try {
            reapAbandonedUserDataImports.reap()
        } catch (e: Exception) {
            logger.error(e) { "import sweep failed" }
        }
    }

    fun stop() {
        sweepScheduler.shutdown()
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}
