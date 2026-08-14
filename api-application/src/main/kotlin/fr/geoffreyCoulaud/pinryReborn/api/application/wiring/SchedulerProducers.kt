package fr.geoffreyCoulaud.pinryReborn.api.application.wiring

import fr.geoffreyCoulaud.pinryReborn.api.worker.PeriodicScheduler
import fr.geoffreyCoulaud.pinryReborn.api.worker.SingleThreadPeriodicScheduler
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.inject.Produces

/**
 * Produces the worker [PeriodicScheduler] as a `@Dependent` bean, so each lifecycle consumer
 * (task poll, export purge, garbage collection, import sweep) gets its own [SingleThreadPeriodicScheduler]
 * instance and therefore its own thread: heavy garbage collection I/O cannot block task claiming,
 * and a slow task poll cannot starve export purge. The isolation lives in the `@Dependent` scope
 * (one instance per injection point), not in a distinct type per scheduler, carrying the role in
 * the wiring itself rather than a string qualifier on a raw `ScheduledExecutorService`.
 */
@ApplicationScoped
class SchedulerProducers {
    @Produces
    @Dependent
    fun periodicScheduler(): PeriodicScheduler = SingleThreadPeriodicScheduler()
}
