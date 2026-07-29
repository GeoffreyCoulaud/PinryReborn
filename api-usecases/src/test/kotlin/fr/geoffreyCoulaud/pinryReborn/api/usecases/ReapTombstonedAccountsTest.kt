package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID.randomUUID

class ReapTombstonedAccountsTest : BaseTest() {
    private val userRepository = mockk<UserRepositoryInterface>()
    private val accountDeletionCleaner = mockk<AccountDeletionCleaner>(relaxed = true)
    private val clock = mockk<Clock>()
    private val tombstoneGrace = Duration.ofHours(24)

    private val reap = ReapTombstonedAccounts(
        userRepository = userRepository,
        accountDeletionCleaner = accountDeletionCleaner,
        clock = clock,
        tombstoneGrace = tombstoneGrace,
    )

    private val now = Instant.parse("2026-07-27T00:00:00Z")
    private val cutoff = now.minus(tombstoneGrace)

    @Test
    fun `Given tombstones older than grace, Then reap re-drives the cleaner on each and returns the count`() {
        // Given: two stale tombstones returned by the repository
        every { clock.now() } returns now
        val tombstone1 = userTombstone()
        val tombstone2 = userTombstone()
        every { userRepository.findTombstonedUsersSoftDeletedBefore(cutoff) } returns listOf(tombstone1, tombstone2)

        // When
        val count = reap.reap()

        // Then: the cutoff is clock.now() minus the grace, each tombstone is re-driven, count returned
        assertEquals(2, count)
        verify { userRepository.findTombstonedUsersSoftDeletedBefore(cutoff) }
        verify { accountDeletionCleaner.deleteAccountData(tombstone1.id) }
        verify { accountDeletionCleaner.deleteAccountData(tombstone2.id) }
    }

    @Test
    fun `Given one re-drive throws, Then the others are still re-driven and no exception propagates`() {
        // Given: the cleaner throws on the middle tombstone; the loop must continue to the rest
        every { clock.now() } returns now
        val tombstone1 = userTombstone()
        val tombstone2 = userTombstone()
        val tombstone3 = userTombstone()
        every { userRepository.findTombstonedUsersSoftDeletedBefore(cutoff) } returns
            listOf(tombstone1, tombstone2, tombstone3)
        every { accountDeletionCleaner.deleteAccountData(tombstone2.id) } throws RuntimeException("db down")

        // When
        val count = reap.reap()

        // Then: every tombstone was attempted (the throw was logged, not propagated), count is the batch size
        verify { accountDeletionCleaner.deleteAccountData(tombstone1.id) }
        verify { accountDeletionCleaner.deleteAccountData(tombstone2.id) }
        verify { accountDeletionCleaner.deleteAccountData(tombstone3.id) }
        assertEquals(3, count)
    }

    private fun userTombstone() = User(
        id = randomUUID(),
        name = "tombstone",
        softDeletedAt = cutoff,
        createdAt = now,
    )
}
