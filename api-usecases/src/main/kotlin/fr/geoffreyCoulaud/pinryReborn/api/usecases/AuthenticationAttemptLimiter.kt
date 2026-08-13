package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.TooManyAuthenticationAttemptsError
import java.time.Duration
import java.time.Instant
import java.util.Collections
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

    private val byExpiry: Comparator<Map.Entry<AuthenticationAttemptKey, AttemptState>> =
        Comparator.comparing { it.value.expiresAt }

    /** Called before the password is verified, so a blocked key costs no hashing. */
    fun check(key: AuthenticationAttemptKey) {
        val now = clock.now()
        val blockedUntil = liveState(key, now)?.blockedUntil
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

    /** The entry for [key] while it is still live; an expired one reads as absent and is purged. */
    private fun liveState(key: AuthenticationAttemptKey, now: Instant): AttemptState? {
        val stored = states[key]
        if (stored != null && !stored.isLiveAt(now)) {
            states.remove(key, stored)
            return null
        }
        return stored
    }

    /** The login key is attacker-supplied and unbounded in cardinality, so the map is bounded and evicts. */
    private fun boundTrackedKeys(now: Instant) {
        if (states.size <= maxTrackedKeys) return
        states.entries.removeIf { !it.value.isLiveAt(now) }
        if (states.size <= maxTrackedKeys) return
        val closestToExpiry = Collections.min(states.entries, byExpiry)
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
