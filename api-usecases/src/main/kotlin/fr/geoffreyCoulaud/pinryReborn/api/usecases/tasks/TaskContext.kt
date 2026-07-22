package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

/**
 * Per-attempt context handed to a [TaskHandler]: the current attempt number, the task's budget, and
 * a lease heartbeat.
 *
 * [renewLease] lets a long-running handler push its lease expiry back so the reaper does not reclaim
 * the task, and a second worker does not start running it concurrently, while it is still working.
 * It defaults to a no-op, so handlers that finish well within one lease never have to think about it.
 * It is deliberately not part of the value identity (only [attempt] and [maxAttempts] are), so two
 * contexts for the same attempt compare equal regardless of which heartbeat they carry.
 */
data class TaskContext(val attempt: Int, val maxAttempts: Int) {
    var renewLease: () -> Unit = {}
}
