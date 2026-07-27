package fr.geoffreyCoulaud.pinryReborn.api.worker

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Periodic scheduler used by the worker lifecycles (task poll, export purge, garbage collection).
 *
 *  Isolation intent: one [SingleThreadPeriodicScheduler] instance per lifecycle consumer, and therefore
 *  one thread per role. The three lifecycles are produced from the composition root, and each producer
 *  instantiates its own [SingleThreadPeriodicScheduler] (see `WorkerLifecycleProducers`), so heavy
 *  garbage collection I/O cannot block task claiming and a slow task poll cannot starve export purge.
 *  This carries the role in the wiring itself, replacing the `@Identifier`-qualified raw
 *  `ScheduledExecutorService` it supersedes. */
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
