package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.BackoffPolicy
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ClaimedTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.exceptions.PermanentTaskException
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/**
 * Executes an already-claimed task through its registered [TaskHandler] and settles it
 * (succeeded / rescheduled with backoff / dead / cancelled) via the fenced [TaskQueueInterface]
 * operations. Does not itself claim tasks from the queue; that is the runtime's job.
 */
@ApplicationScoped
class TaskProcessor(
    private val taskQueue: TaskQueueInterface,
    private val registry: TaskHandlerRegistry,
    private val backoffPolicy: BackoffPolicy,
    private val clock: Clock,
) {
    private sealed interface Outcome
    private data object Success : Outcome
    private data class Retryable(val message: String) : Outcome
    private data class Permanent(val message: String) : Outcome

    fun execute(claimed: ClaimedTask) {
        if (claimed.cancelRequested) {
            taskQueue.markCancelledIfRequested(claimed.id, claimed.leaseId, clock.now())
            return
        }
        val handler = registry.handlerFor(claimed.kind)
        if (handler == null) {
            taskQueue.markDead(claimed.id, claimed.leaseId, clock.now(), "no handler for kind ${claimed.kind}")
        } else {
            val outcome = runHandler(handler, claimed.payload)
            val now = clock.now()
            if (taskQueue.markCancelledIfRequested(claimed.id, claimed.leaseId, now)) {
                return
            }
            settle(claimed, outcome, now)
        }
    }

    private fun settle(claimed: ClaimedTask, outcome: Outcome, now: Instant) {
        when (outcome) {
            is Success -> taskQueue.markSucceeded(claimed.id, claimed.leaseId, now)
            is Permanent -> taskQueue.markDead(claimed.id, claimed.leaseId, now, outcome.message)
            is Retryable ->
                if (claimed.attempts >= claimed.maxAttempts) {
                    taskQueue.markDead(claimed.id, claimed.leaseId, now, outcome.message)
                } else {
                    val retryAt = backoffPolicy.nextAttemptAt(claimed.attempts, now)
                    taskQueue.markPendingRetry(claimed.id, claimed.leaseId, retryAt, now, outcome.message)
                }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runHandler(handler: TaskHandler, payload: String): Outcome =
        try {
            handler.handle(payload)
            Success
        } catch (e: PermanentTaskException) {
            Permanent(e.reason)
        } catch (e: Exception) {
            Retryable(e.message ?: "transient failure")
        }
}
