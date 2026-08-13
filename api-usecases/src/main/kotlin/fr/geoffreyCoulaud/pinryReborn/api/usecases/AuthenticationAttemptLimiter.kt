package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.TooManyAuthenticationAttemptsError
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Refuses an attempt once a key has failed [threshold] times in a row, for a block that walks up
 * [backoffSteps]. In-process counters, behind no port (`docs/adr/0013-in-memory-authentication-attempt-limiting.md`).
 */
class AuthenticationAttemptLimiter(
    private val clock: Clock,
    private val threshold: Int,
    private val backoffSteps: List<Duration>,
    private val forgetAfter: Duration,
    private val maxTrackedKeys: Int,
) {
    private val states = ConcurrentHashMap<AuthenticationAttemptKey, AttemptState>()

    // No policy value may turn the limiter off (spec D10): each bound below excludes a value at
    // which it stops limiting, silently and with nothing to see in a log.
    init {
        require(threshold >= 1) { "threshold must be at least 1, was $threshold" }
        require(backoffSteps.isNotEmpty()) { "backoffSteps must hold at least one step" }
        require(backoffSteps.all { it > Duration.ZERO }) { "every backoff step must be positive, was $backoffSteps" }
        require(forgetAfter > Duration.ZERO) { "forgetAfter must be positive, was $forgetAfter" }
        require(maxTrackedKeys >= 1) { "maxTrackedKeys must be at least 1, was $maxTrackedKeys" }
    }

    /** Called before the password is verified, so a blocked key costs no hashing. */
    fun check(key: AuthenticationAttemptKey) {
        val now = clock.now()
        // An entry outlives the block it carries, so an expired one is never still blocked: this
        // read needs no expiry test of its own. The failure count reads the expiry, in nextState.
        val blockedUntil = states[key]?.blockedUntil
        if (blockedUntil != null && blockedUntil.isAfter(now)) {
            throw TooManyAuthenticationAttemptsError(wholeSecondsBetween(now, blockedUntil))
        }
    }

    fun recordFailure(key: AuthenticationAttemptKey) {
        val now = clock.now()
        // compute() rather than a read followed by a write: parallel failures on one key are what
        // this limiter exists for, and a lost increment is a guess it forgot to count.
        states.compute(key) { _, stored -> nextState(stored, now) }
        boundTrackedKeys(now)
    }

    fun recordSuccess(key: AuthenticationAttemptKey) {
        states.remove(key)
    }

    private fun nextState(stored: AttemptState?, now: Instant): AttemptState {
        val failures = if (stored != null && stored.isLiveAt(now)) stored.failures + 1 else 1
        val blockedUntil = if (failures >= threshold) now.plus(backoffStep(failures)) else null
        val forgetAt = now.plus(forgetAfter)
        // The entry outlives the block it carries, so a key cannot be forgotten while still blocked.
        val expiresAt = if (blockedUntil != null && blockedUntil.isAfter(forgetAt)) blockedUntil else forgetAt
        return AttemptState(failures, blockedUntil, expiresAt)
    }

    /** The step earned by [failures], the last one saturating. */
    private fun backoffStep(failures: Int): Duration = backoffSteps[minOf(failures - threshold, backoffSteps.lastIndex)]

    /** The login key is attacker-supplied and unbounded in cardinality, so the map is bounded and evicts. */
    private fun boundTrackedKeys(now: Instant) {
        if (states.size <= maxTrackedKeys) return
        states.entries.removeIf { !it.value.isLiveAt(now) }
        if (states.size <= maxTrackedKeys) return
        val closestToExpiry = states.entries.minBy { it.value.expiresAt }
        states.remove(closestToExpiry.key, closestToExpiry.value)
    }

    /** Whole seconds, rounded up: a fraction of a second still costs the caller a whole one. */
    private fun wholeSecondsBetween(from: Instant, to: Instant): Long {
        val remaining = Duration.between(from, to)
        return if (remaining.nano == 0) remaining.seconds else remaining.seconds + 1
    }

    private fun AttemptState.isLiveAt(now: Instant) = expiresAt.isAfter(now)

    /** [expiresAt] is when the counter is forgotten, and never falls before [blockedUntil]. */
    private data class AttemptState(
        val failures: Int,
        val blockedUntil: Instant?,
        val expiresAt: Instant,
    )
}
