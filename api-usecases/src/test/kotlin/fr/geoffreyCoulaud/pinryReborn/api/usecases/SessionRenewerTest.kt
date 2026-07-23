package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.IssuedSession
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.SessionExpiryPolicy
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.TokenGenerator
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID.randomUUID

class SessionRenewerTest {
    private val repository = mockk<SessionTokenRepositoryInterface>(relaxed = true)
    private val tokenGenerator = mockk<TokenGenerator>()
    private val clock = mockk<Clock>()
    private val policy = SessionExpiryPolicy(Duration.ofDays(30), Duration.ofHours(12), 0.75)
    private val transactionRunner = mockk<TransactionRunner>()
    private val renewer = SessionRenewer(repository, tokenGenerator, clock, policy, transactionRunner)

    private val now = Instant.parse("2026-07-21T00:00:00Z")
    private val user = User(id = randomUUID(), name = "alice", createdAt = Instant.now())
    private val current = SessionToken(randomUUID(), user, expiresAt = now.plusSeconds(10), persistent = true)

    // Passthrough so the transactional block runs in the behavioral tests; overridden where a test
    // needs to prove the writes live inside the block.
    @BeforeEach
    fun stubTransactionRunnerPassthrough() {
        every { transactionRunner.inTransaction<IssuedSession>(any()) } answers
            { firstArg<() -> IssuedSession>().invoke() }
    }

    @Test
    fun `Given a current token, Then renew issues a new token preserving persistent and deletes the old`() {
        every { tokenGenerator.generateToken() } returns "new-token"
        every { clock.now() } returns now

        val issued = renewer.renew(current)

        val expectedExpiry = now.plus(Duration.ofDays(30))
        assertEquals("new-token", issued.token)
        assertEquals(expectedExpiry, issued.expiresAt)
        val saved = slot<SessionToken>()
        verify { repository.saveSessionToken(capture(saved), TokenHasher.sha256("new-token")) }
        assertEquals(user, saved.captured.user)
        assertEquals(true, saved.captured.persistent)
        verify { repository.deleteById(current.id) }
    }

    @Test
    fun `Given renew, Then the new token is saved before the old is deleted`() {
        every { tokenGenerator.generateToken() } returns "new-token"
        every { clock.now() } returns now
        renewer.renew(current)
        verifyOrder {
            repository.saveSessionToken(any(), any())
            repository.deleteById(current.id)
        }
    }

    @Test
    fun `Given the new token save fails, Then the old token is not deleted (no half-rotation)`() {
        every { tokenGenerator.generateToken() } returns "new-token"
        every { clock.now() } returns now
        every { repository.saveSessionToken(any(), any()) } throws IllegalStateException("db down")

        assertThrows<IllegalStateException> { renewer.renew(current) }
        verify(exactly = 0) { repository.deleteById(any()) }
    }

    @Test
    fun `Given renew, Then both the save and the delete run inside the transaction`() {
        // Given the transaction runner never invokes its block (no-op)
        every { transactionRunner.inTransaction<IssuedSession>(any()) } returns IssuedSession("x", now, now)

        // When
        renewer.renew(current)

        // Then neither write happened, proving both live inside the transactional block
        verify(exactly = 0) { repository.saveSessionToken(any(), any()) }
        verify(exactly = 0) { repository.deleteById(any()) }
    }

    @Test
    fun `Given an ephemeral current token, Then renew keeps persistent false and uses the ephemeral TTL`() {
        val ephemeralCurrent = SessionToken(randomUUID(), user, expiresAt = now.plusSeconds(5), persistent = false)
        every { tokenGenerator.generateToken() } returns "new-token"
        every { clock.now() } returns now

        val issued = renewer.renew(ephemeralCurrent)

        val expectedExpiry = now.plus(Duration.ofHours(12))
        assertEquals(expectedExpiry, issued.expiresAt)
        val saved = slot<SessionToken>()
        verify { repository.saveSessionToken(capture(saved), any()) }
        assertEquals(false, saved.captured.persistent)
    }
}
