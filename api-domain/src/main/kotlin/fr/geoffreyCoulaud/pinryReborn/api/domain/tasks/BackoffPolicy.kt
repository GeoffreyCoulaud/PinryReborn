package fr.geoffreyCoulaud.pinryReborn.api.domain.tasks

import java.time.Duration
import java.time.Instant

interface BackoffPolicy {
    /** [floor] is the calling task's own minimum delay; a task that declares none passes [Duration.ZERO]. */
    fun nextAttemptAt(attempts: Int, now: Instant, floor: Duration): Instant
}

class ExponentialBackoffWithJitter(
    private val base: Duration,
    private val cap: Duration,
    private val random: () -> Double,
) : BackoffPolicy {
    override fun nextAttemptAt(attempts: Int, now: Instant, floor: Duration): Instant {
        val exponent = when {
            attempts <= 1 -> 0
            attempts - 1 > MAX_EXPONENT -> MAX_EXPONENT
            else -> attempts - 1
        }
        val window = base.multipliedBy(1L shl exponent)
        val bounded = if (window > cap) cap else window
        val delayNanos = (bounded.toNanos() * random()).toLong()
        // After the jitter, and over the cap: the cap bounds the queue's window, the floor is the
        // task's minimum, and a task whose retries must outlast an operator is not capped back down.
        return now.plusNanos(maxOf(delayNanos, floor.toNanos()))
    }

    private companion object {
        const val MAX_EXPONENT = 30
    }
}
