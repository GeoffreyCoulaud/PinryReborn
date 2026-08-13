package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BaseError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ThrottledError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.TooManyAuthenticationAttemptsError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UserAuthenticationError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import java.time.Instant
import java.util.UUID.randomUUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class AuthenticationAttemptLimiterTest : BaseTest() {
    private val clock = mockk<Clock>()
    private val threshold = 5
    private val backoffSteps = listOf(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10))
    private val forgetAfter = Duration.ofMinutes(15)
    private val limiter = limiterWith(forgetAfter = forgetAfter, maxTrackedKeys = 100)

    private val key = AuthenticationAttemptKey.forLogin("alice")
    private val otherKey = AuthenticationAttemptKey.forLogin("bob")
    private val thirdKey = AuthenticationAttemptKey.forLogin("carol")

    private val start = Instant.parse("2026-08-13T10:00:00Z")
    private var now = start

    @BeforeEach
    fun stubTheClock() {
        every { clock.now() } answers { now }
    }

    private fun limiterWith(
        threshold: Int = this.threshold,
        backoffSteps: List<Duration> = this.backoffSteps,
        forgetAfter: Duration = this.forgetAfter,
        maxTrackedKeys: Int = 100,
    ) = AuthenticationAttemptLimiter(clock, threshold, backoffSteps, forgetAfter, maxTrackedKeys)

    private fun failTimes(limiter: AuthenticationAttemptLimiter, key: AuthenticationAttemptKey, count: Int) =
        repeat(count) { limiter.recordFailure(key) }

    private fun refusalFor(limiter: AuthenticationAttemptLimiter, key: AuthenticationAttemptKey) =
        assertThrows<TooManyAuthenticationAttemptsError> { limiter.check(key) }

    @Test
    fun `Given a threshold below one, Then the limiter refuses to be built`() {
        // Then: a threshold of zero would refuse a key that never failed
        assertThrows<IllegalArgumentException> { limiterWith(threshold = 0) }
    }

    @Test
    fun `Given no backoff step, Then the limiter refuses to be built`() {
        // Then: there would be no block to serve once the threshold is reached
        assertThrows<IllegalArgumentException> { limiterWith(backoffSteps = emptyList()) }
    }

    @Test
    fun `Given a bound below one key, Then the limiter refuses to be built`() {
        // Then: every insertion would exceed the bound and evict itself, an off switch spec D10 denies
        assertThrows<IllegalArgumentException> { limiterWith(maxTrackedKeys = 0) }
    }

    @Test
    fun `Given no recorded failure, Then the check passes`() {
        assertDoesNotThrow { limiter.check(key) }
    }

    @Test
    fun `Given fewer failures than the threshold, Then the check passes`() {
        // Given
        failTimes(limiter, key, threshold - 1)
        // Then
        assertDoesNotThrow { limiter.check(key) }
    }

    @Test
    fun `Given the threshold is reached, Then the check is refused for the first backoff step`() {
        // Given
        failTimes(limiter, key, threshold)
        // Then
        assertEquals(30, refusalFor(limiter, key).retryAfterSeconds)
    }

    @Test
    fun `Given the refusal, Then it is a throttled error that no authentication handler rewrites`() {
        // Given
        failTimes(limiter, key, threshold)
        // When: read through the type the presentation layer dispatches on
        val error: BaseError = refusalFor(limiter, key)
        // Then: SessionController catches UserAuthenticationError and answers 401, which would
        // swallow the 429 the ThrottledError marker earns (spec D7 and D8).
        assertTrue(error is ThrottledError)
        assertFalse(error is UserAuthenticationError)
    }

    @Test
    fun `Given one failure past the threshold, Then the block walks up to the second step`() {
        // Given
        failTimes(limiter, key, threshold + 1)
        // Then
        assertEquals(120, refusalFor(limiter, key).retryAfterSeconds)
    }

    @Test
    fun `Given more failures than there are steps, Then the last step saturates`() {
        // Given: five failures past the threshold, for a list of three steps
        failTimes(limiter, key, threshold + 5)
        // Then
        assertEquals(600, refusalFor(limiter, key).retryAfterSeconds)
    }

    @Test
    fun `Given the block has elapsed, Then the check passes again`() {
        // Given
        failTimes(limiter, key, threshold)
        // When: one second past the first step
        now = start.plusSeconds(31)
        // Then
        assertDoesNotThrow { limiter.check(key) }
    }

    @Test
    fun `Given part of a second is left on the block, Then the retry delay is a whole second`() {
        // Given
        failTimes(limiter, key, threshold)
        // When: half a second of the thirty is left
        now = start.plusSeconds(29).plusMillis(500)
        // Then
        assertEquals(1, refusalFor(limiter, key).retryAfterSeconds)
    }

    @Test
    fun `Given whole seconds and a fraction are left on the block, Then the retry delay rounds up`() {
        // Given
        failTimes(limiter, key, threshold)
        // When: twenty-nine seconds and a half are left of the thirty
        now = start.plusMillis(500)
        // Then: rounded up, where a truncation would answer twenty-nine
        assertEquals(30, refusalFor(limiter, key).retryAfterSeconds)
    }

    @Test
    fun `Given a success, Then the counter starts over`() {
        // Given: one failure short of the threshold, then a success
        failTimes(limiter, key, threshold - 1)
        limiter.recordSuccess(key)
        // When: the threshold is reached again
        failTimes(limiter, key, threshold)
        // Then: the block is the first step, so the failures before the success were dropped
        assertEquals(30, refusalFor(limiter, key).retryAfterSeconds)
    }

    @Test
    fun `Given a counter left idle past the forget-after, Then the check passes`() {
        // Given: a blocked key
        failTimes(limiter, key, threshold)
        // When: the counter sits idle past the forget-after
        now = start.plus(forgetAfter).plusSeconds(1)
        // Then: the entry reads as absent
        assertDoesNotThrow { limiter.check(key) }
    }

    @Test
    fun `Given a failure landing on a forgotten counter, Then the failures start over`() {
        // Given: a blocked key, left idle past the forget-after
        failTimes(limiter, key, threshold)
        now = start.plus(forgetAfter).plusSeconds(1)
        // When: the failures resume, without a check purging the expired entry first
        failTimes(limiter, key, threshold - 1)
        // Then: they counted from the first, so the earlier ones were forgotten
        assertDoesNotThrow { limiter.check(key) }
    }

    @Test
    fun `Given a block outlasting the forget-after, Then the entry survives until the block ends`() {
        // Given: a policy forgetting a counter long before its first step ends
        val shortMemory = limiterWith(forgetAfter = Duration.ofSeconds(1), maxTrackedKeys = 100)
        failTimes(shortMemory, key, threshold)
        // When: the forget-after has passed but the thirty-second block has not
        now = start.plusSeconds(10)
        // Then
        assertEquals(20, refusalFor(shortMemory, key).retryAfterSeconds)
        // And: the failures survived too, so the next one walks up instead of starting over
        failTimes(shortMemory, key, 1)
        assertEquals(120, refusalFor(shortMemory, key).retryAfterSeconds)
    }

    @Test
    fun `Given two logins differing only in case, Then they share one counter`() {
        // Given
        failTimes(limiter, AuthenticationAttemptKey.forLogin("Alice"), threshold - 1)
        // When
        failTimes(limiter, AuthenticationAttemptKey.forLogin("aLICE"), 1)
        // Then
        refusalFor(limiter, AuthenticationAttemptKey.forLogin("alice"))
    }

    @Test
    fun `Given the threshold is reached on a user key, Then the check is refused`() {
        // Given: re-authentication and password change share this counter (D4)
        val userKey = AuthenticationAttemptKey.forUser(randomUUID())
        failTimes(limiter, userKey, threshold)
        // Then
        assertEquals(30, refusalFor(limiter, userKey).retryAfterSeconds)
    }

    @Test
    fun `Given one user is blocked, Then another user's check passes`() {
        // Given
        failTimes(limiter, AuthenticationAttemptKey.forUser(randomUUID()), threshold)
        // Then: the counter is keyed by the user, not shared by every user
        assertDoesNotThrow { limiter.check(AuthenticationAttemptKey.forUser(randomUUID())) }
    }

    @Test
    fun `Given a login name equal to a user id, Then the two counters stay apart`() {
        // Given
        val userId = randomUUID()
        failTimes(limiter, AuthenticationAttemptKey.forLogin(userId.toString()), threshold)
        // Then
        assertDoesNotThrow { limiter.check(AuthenticationAttemptKey.forUser(userId)) }
    }

    @Test
    fun `Given the tracked keys pass the bound with an expired entry, Then the live counters stay`() {
        // Given: a bound of two, an entry expiring first and a blocked entry outliving it
        val bounded = limiterWith(forgetAfter = forgetAfter, maxTrackedKeys = 2)
        failTimes(bounded, thirdKey, 1)
        now = start.plus(forgetAfter).minusSeconds(60)
        failTimes(bounded, key, threshold)
        // When: a third key crosses the bound, after the first entry has expired
        now = start.plus(forgetAfter).plusSeconds(60)
        failTimes(bounded, otherKey, 1)
        // Then: the blocked counter survived the crossing, so its next failure is the second step
        failTimes(bounded, key, 1)
        assertEquals(120, refusalFor(bounded, key).retryAfterSeconds)
    }

    @Test
    fun `Given the tracked keys pass the bound with none expired, Then the entry closest to expiry goes`() {
        // Given: a bound of two and two blocked counters, the first one closest to expiry
        val bounded = limiterWith(forgetAfter = forgetAfter, maxTrackedKeys = 2)
        failTimes(bounded, key, threshold)
        now = start.plusSeconds(1)
        failTimes(bounded, otherKey, threshold)
        // When: a third key crosses the bound with nothing expired
        now = start.plusSeconds(2)
        failTimes(bounded, thirdKey, 1)
        // Then: the earliest-expiring counter was evicted, and the other one is untouched
        assertDoesNotThrow { bounded.check(key) }
        refusalFor(bounded, otherKey)
    }
}
