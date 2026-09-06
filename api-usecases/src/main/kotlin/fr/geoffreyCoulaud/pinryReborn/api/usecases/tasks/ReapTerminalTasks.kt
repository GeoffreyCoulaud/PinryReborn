package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import java.time.Duration

/**
 * Reclaim terminal task rows (`SUCCEEDED`, `DEAD`, `CANCELLED`) older than [terminalTaskGrace],
 * delegating to [TaskQueueInterface.deleteTerminalBefore]. The grace keeps recent history around
 * (a DEAD task an operator might investigate).
 *
 * Not `@ApplicationScoped`: [terminalTaskGrace] is a primitive ARC cannot resolve, so the bean is
 * produced in wiring (`GarbageCollectionProducers`), mirroring `ExportProducers` for
 * `ReapUserDataExports`.
 *
 * Logger-free: the lifecycle `safeAll` logs a sweep-level throw, and the count is returned for the
 * eventual metrics surface (same shape as `ReapExpiredTasks`).
 */
class ReapTerminalTasks(
    private val taskQueue: TaskQueueInterface,
    private val clock: Clock,
    private val terminalTaskGrace: Duration,
) {
    fun reap(): Int = taskQueue.deleteTerminalBefore(clock.now() - terminalTaskGrace)
}
