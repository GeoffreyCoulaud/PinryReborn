package fr.geoffreyCoulaud.pinryReborn.api.worker

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Periodic scheduler used by the worker lifecycles (task poll, export purge, garbage collection).
 *
 *  Isolation intent: one [SingleThreadPeriodicScheduler] instance per lifecycle consumer, and therefore
 *  one thread per role. The scheduler is produced as a `@Dependent` bean in the composition root (see
 *  `SchedulerProducers`), so each lifecycle that injects it gets its own instance: heavy garbage
 *  collection I/O cannot block task claiming, and a slow task poll cannot starve export purge. The role
 *  is carried by the wiring, not by a string qualifier on a raw `ScheduledExecutorService`. */
interface PeriodicScheduler {
    fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit)
    fun shutdown()
}

class SingleThreadPeriodicScheduler : PeriodicScheduler {
    private val delegate = Executors.newSingleThreadScheduledExecutor()

    override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit) {
        delegate.scheduleWithFixedDelay(command, initialDelay, period, unit)
    }

    override fun shutdown() {
        delegate.shutdown()
    }
}
