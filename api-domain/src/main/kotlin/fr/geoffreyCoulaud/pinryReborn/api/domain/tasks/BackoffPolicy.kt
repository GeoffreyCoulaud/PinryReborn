package fr.geoffreyCoulaud.pinryReborn.api.domain.tasks

import java.time.Duration
import java.time.Instant

interface BackoffPolicy {
    fun nextAttemptAt(attempts: Int, now: Instant): Instant
}

class ExponentialBackoffWithJitter(
    private val base: Duration,
    private val cap: Duration,
    private val random: () -> Double,
) : BackoffPolicy {
    override fun nextAttemptAt(attempts: Int, now: Instant): Instant {
        val exponent = when {
            attempts <= 1 -> 0
            attempts - 1 > MAX_EXPONENT -> MAX_EXPONENT
            else -> attempts - 1
        }
        val window = base.multipliedBy(1L shl exponent)
        val bounded = if (window > cap) cap else window
        val delayNanos = (bounded.toNanos() * random()).toLong()
        return now.plusNanos(delayNanos)
    }

    private companion object {
        const val MAX_EXPONENT = 30
    }
}
