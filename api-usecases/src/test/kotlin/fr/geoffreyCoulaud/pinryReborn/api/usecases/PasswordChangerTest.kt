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
    private val changer = PasswordChanger(passwords, hasher, sessionRevoker, tx, clock, minimumInterval)

    private val now = Instant.parse("2026-07-29T00:00:00Z")
    private val user = User(id = randomUUID(), name = "u", createdAt = TestTime.now)
    // One minute before `now`, so the pre-existing tests sit outside the 30 s interval.
    private val current =
        HashedPassword("current-hash", PasswordHashAlgorithm.BCRYPT, createdAt = now.minus(Duration.ofMinutes(1)))

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
        // Then: the interval is still measured from `current`'s createdAt (outside the interval),
        // so a correct change afterwards still succeeds. This is D10: the limit counts successful
        // changes, not attempts.
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
}
