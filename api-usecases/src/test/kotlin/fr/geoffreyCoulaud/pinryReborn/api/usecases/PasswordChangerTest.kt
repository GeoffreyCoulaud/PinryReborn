package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordChangeCollisionException
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PasswordChangeCollisionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PasswordChangedTooSoonError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PasswordPreviouslyUsedError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ReauthenticationError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.TooManyAuthenticationAttemptsError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID.randomUUID

class PasswordChangerTest : BaseTest() {
    private val passwords = mockk<UserPasswordHashRepositoryInterface>()
    private val hasher = mockk<PasswordHasher>()
    private val sessionRevoker = mockk<SessionRevoker>(relaxed = true)
    private val tx = mockk<TransactionRunner>()
    private val clock = mockk<Clock>()
    private val minimumInterval = Duration.ofSeconds(30)
    private val threshold = 3

    // Its own clock, so the instants the change itself is measured against stay a per-test choice.
    // Built lazily, from the test body: BaseTest clears every stub after the instance is constructed.
    private val limiterClock = mockk<Clock>()
    private val limiter by lazy {
        every { limiterClock.now() } returns TestTime.now
        AuthenticationAttemptLimiter(
            clock = limiterClock,
            threshold = threshold,
            backoffSteps = listOf(Duration.ofSeconds(30)),
            forgetAfter = Duration.ofMinutes(15),
            maxTrackedKeys = 100,
        )
    }
    private val changer by lazy {
        PasswordChanger(passwords, hasher, sessionRevoker, tx, clock, limiter, minimumInterval)
    }

    private val now = Instant.parse("2026-07-29T00:00:00Z")
    private val user = User(id = randomUUID(), name = "u", createdAt = TestTime.now)
    // One minute before `now`, so the pre-existing tests sit outside the 30 s interval.
    private val current =
        HashedPassword("current-hash", PasswordHashAlgorithm.BCRYPT, createdAt = now.minus(Duration.ofMinutes(1)))

    private fun failTimes(count: Int) =
        repeat(count) { assertThrows<ReauthenticationError> { changer.changePassword(user, "bad", "new") } }

    @Test
    fun `Given valid inputs, Then the new hash is appended, stamped from the clock, and all sessions revoked`() {
        // Given
        every { tx.inTransaction(any<() -> Any?>()) } answers { (firstArg<() -> Any?>())() }
        every { passwords.findCurrentPasswordHash(user) } returns current
        every { hasher.matches("old", current) } returns true
        every { passwords.findAllPasswordHashesForUser(user) } returns listOf(current)
        every { hasher.matches("new", current) } returns false
        every { clock.now() } returns now
        val stamped = HashedPassword("new-hash", PasswordHashAlgorithm.BCRYPT, createdAt = now)
        every { hasher.hash("new", now) } returns stamped
        val saved = slot<HashedPassword>()
        every { passwords.saveUserPasswordHash(user, capture(saved)) } returns stamped
        // When
        changer.changePassword(user, "old", "new")
        // Then
        assertEquals(now, saved.captured.createdAt)
        verify { sessionRevoker.revokeAll(user) }
    }

    @Test
    fun `Given a wrong current password, Then it throws and nothing is written`() {
        every { passwords.findCurrentPasswordHash(user) } returns current
        every { hasher.matches("bad", current) } returns false
        assertThrows<ReauthenticationError> { changer.changePassword(user, "bad", "new") }
        verify(exactly = 0) { passwords.saveUserPasswordHash(any(), any()) }
    }

    @Test
    fun `Given no stored hash, Then re-authentication fails`() {
        every { passwords.findCurrentPasswordHash(user) } returns null
        assertThrows<ReauthenticationError> { changer.changePassword(user, "x", "new") }
    }

    @Test
    fun `Given a previously-used new password, Then it throws PasswordPreviouslyUsedError`() {
        every { passwords.findCurrentPasswordHash(user) } returns current
        every { hasher.matches("old", current) } returns true
        every { clock.now() } returns now
        val older = HashedPassword("older", PasswordHashAlgorithm.BCRYPT, createdAt = TestTime.now)
        every { passwords.findAllPasswordHashesForUser(user) } returns listOf(current, older)
        every { hasher.matches("reused", current) } returns false
        every { hasher.matches("reused", older) } returns true
        assertThrows<PasswordPreviouslyUsedError> { changer.changePassword(user, "old", "reused") }
    }

    @Test
    fun `Given a change inside the minimum interval, Then it throws PasswordChangedTooSoonError`() {
        // Given
        val recent = HashedPassword("h", PasswordHashAlgorithm.BCRYPT, createdAt = now.minusSeconds(10))
        every { passwords.findCurrentPasswordHash(user) } returns recent
        every { hasher.matches("old", recent) } returns true
        every { clock.now() } returns now
        // When / Then: 30 s interval, 10 s elapsed -> 20 s remaining
        val error = assertThrows<PasswordChangedTooSoonError> { changer.changePassword(user, "old", "new") }
        assertEquals(20, error.retryAfterSeconds)
        verify(exactly = 0) { passwords.saveUserPasswordHash(any(), any()) }
    }

    @Test
    fun `Given a fraction of a second left on the interval, Then the retry delay rounds up`() {
        // Given: 30 s interval, 9.5 s elapsed -> 20.5 s remaining
        val recent =
            HashedPassword("h", PasswordHashAlgorithm.BCRYPT, createdAt = now.minusSeconds(10).plusMillis(500))
        every { passwords.findCurrentPasswordHash(user) } returns recent
        every { hasher.matches("old", recent) } returns true
        every { clock.now() } returns now
        // When / Then: rounded up, where a truncation would answer twenty and retry a second early
        val error = assertThrows<PasswordChangedTooSoonError> { changer.changePassword(user, "old", "new") }
        assertEquals(21, error.retryAfterSeconds)
    }

    @Test
    fun `Given a change at the interval boundary, Then it succeeds`() {
        // Given: createdAt exactly `interval` ago is allowed (the refusal is strictly inside)
        every { tx.inTransaction(any<() -> Any?>()) } answers { (firstArg<() -> Any?>())() }
        val boundary = HashedPassword("h", PasswordHashAlgorithm.BCRYPT, createdAt = now.minusSeconds(30))
        every { passwords.findCurrentPasswordHash(user) } returns boundary
        every { hasher.matches("old", boundary) } returns true
        every { passwords.findAllPasswordHashesForUser(user) } returns listOf(boundary)
        every { hasher.matches("new", boundary) } returns false
        every { clock.now() } returns now
        val newHash = HashedPassword("new-hash", PasswordHashAlgorithm.BCRYPT, createdAt = now)
        every { hasher.hash("new", now) } returns newHash
        every { passwords.saveUserPasswordHash(any(), any()) } returns newHash
        // When / Then
        changer.changePassword(user, "old", "new")
        verify { passwords.saveUserPasswordHash(any(), any()) }
    }

    @Test
    fun `Given a failed reauthentication, Then no hash is written so a later change is still allowed`() {
        // Given: a wrong current password fails and writes nothing
        every { passwords.findCurrentPasswordHash(user) } returns current
        every { hasher.matches("bad", current) } returns false
        assertThrows<ReauthenticationError> { changer.changePassword(user, "bad", "new") }
        verify(exactly = 0) { passwords.saveUserPasswordHash(any(), any()) }
        // Then: the interval is still measured from `current`'s createdAt (outside the interval), so
        // a correct change afterwards still succeeds. This is D10 of the older
        // docs/specs/2026-07-31-current-password-determinism.md, not this lot's: the limit counts
        // successful changes, not attempts.
        every { tx.inTransaction(any<() -> Any?>()) } answers { (firstArg<() -> Any?>())() }
        every { hasher.matches("old", current) } returns true
        every { passwords.findAllPasswordHashesForUser(user) } returns listOf(current)
        every { hasher.matches("new", current) } returns false
        every { clock.now() } returns now
        val newHash = HashedPassword("new-hash", PasswordHashAlgorithm.BCRYPT, createdAt = now)
        every { hasher.hash("new", now) } returns newHash
        every { passwords.saveUserPasswordHash(any(), any()) } returns newHash
        changer.changePassword(user, "old", "new")
    }

    @Test
    fun `Given the repository signals a collision, Then PasswordChanger rethrows PasswordChangeCollisionError`() {
        // Given
        every { tx.inTransaction(any<() -> Any?>()) } answers { (firstArg<() -> Any?>())() }
        every { passwords.findCurrentPasswordHash(user) } returns current
        every { hasher.matches("old", current) } returns true
        every { passwords.findAllPasswordHashesForUser(user) } returns listOf(current)
        every { hasher.matches("new", current) } returns false
        every { clock.now() } returns now
        val newHash = HashedPassword("new-hash", PasswordHashAlgorithm.BCRYPT, createdAt = now)
        every { hasher.hash("new", now) } returns newHash
        val violation = Exception("unique constraint violated")
        every { passwords.saveUserPasswordHash(any(), any()) } throws PasswordChangeCollisionException(violation)
        // When / Then
        assertThrows<PasswordChangeCollisionError> { changer.changePassword(user, "old", "new") }
    }

    @Test
    fun `Given the threshold reached on wrong current passwords, Then the next change is refused`() {
        // Given
        every { passwords.findCurrentPasswordHash(user) } returns current
        every { hasher.matches("bad", current) } returns false
        failTimes(threshold)
        // When
        val error = assertThrows<TooManyAuthenticationAttemptsError> { changer.changePassword(user, "bad", "new") }
        // Then
        assertEquals(30, error.retryAfterSeconds)
    }

    @Test
    fun `Given a blocked user, Then no password reaches the hasher`() {
        // Given
        every { passwords.findCurrentPasswordHash(user) } returns current
        every { hasher.matches("bad", current) } returns false
        failTimes(threshold)
        // When
        assertThrows<TooManyAuthenticationAttemptsError> { changer.changePassword(user, "bad", "new") }
        // Then: one hash per attempt up to the block, none for the refused one (spec D6)
        verify(exactly = threshold) { hasher.matches(any(), any()) }
    }

    @Test
    fun `Given a change refused on another rule, Then the proven password still clears the counter`() {
        // Given: one failure short of the threshold
        every { passwords.findCurrentPasswordHash(user) } returns current
        every { hasher.matches("bad", current) } returns false
        failTimes(threshold - 1)
        // When: the right current password, refused for reusing an old one
        every { hasher.matches("old", current) } returns true
        every { clock.now() } returns now
        every { passwords.findAllPasswordHashesForUser(user) } returns listOf(current)
        every { hasher.matches("reused", current) } returns true
        assertThrows<PasswordPreviouslyUsedError> { changer.changePassword(user, "old", "reused") }
        // Then: the counter limits password guesses, and that caller guessed none wrong
        failTimes(threshold - 1)
    }

    @Test
    fun `Given a change refused as too soon, Then that refusal is not counted as a guess`() {
        // Given: the current password proven, the change refused for landing inside the interval
        val recent = HashedPassword("h", PasswordHashAlgorithm.BCRYPT, createdAt = now.minusSeconds(10))
        every { passwords.findCurrentPasswordHash(user) } returns recent
        every { hasher.matches("old", recent) } returns true
        every { clock.now() } returns now
        assertThrows<PasswordChangedTooSoonError> { changer.changePassword(user, "old", "new") }
        // When / Then: that caller guessed nothing wrong, so a whole threshold of wrong passwords
        // still answers the ordinary refusal rather than the block
        every { hasher.matches("bad", recent) } returns false
        failTimes(threshold)
    }

    @Test
    fun `Given the threshold reached on re-authentication, Then the password change is refused too`() {
        // Given: both verify the same secret, so they share one counter (spec D4)
        val reauth = Reauthenticator(passwords, hasher, limiter)
        every { passwords.findCurrentPasswordHash(user) } returns current
        every { hasher.matches("bad", current) } returns false
        repeat(threshold) { assertThrows<ReauthenticationError> { reauth.reauthenticate(user, "bad") } }
        // When / Then
        assertThrows<TooManyAuthenticationAttemptsError> { changer.changePassword(user, "bad", "new") }
    }
}
