package fr.geoffreyCoulaud.pinryReborn.api.worker

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Dedicated scheduler for the periodic garbage collection sweeps, isolated from the task poll and
 *  export purge schedulers so heavy filesystem I/O does not block task claiming. */
interface GarbageCollectionExecutor {
    fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit)
    fun shutdown()
}

class SingleThreadGarbageCollectionExecutor(
    private val delegate: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
) : GarbageCollectionExecutor {
    override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit) {
        delegate.scheduleWithFixedDelay(command, initialDelay, period, unit)
    }
    override fun shutdown() {
        delegate.shutdown()
    }
}
