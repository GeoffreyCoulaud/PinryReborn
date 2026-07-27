package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class ReapExpiredSessionTokensTest {
    private val sessionTokenRepository = mockk<SessionTokenRepositoryInterface>()
    private val clock = mockk<Clock>()
    private val reaper = ReapExpiredSessionTokens(sessionTokenRepository, clock)

    private val now = Instant.parse("2026-07-27T00:00:00Z")

    @Test
    fun `Given expired tokens exist, Then reap deletes them before now and returns the count`() {
        // Given
        every { clock.now() } returns now
        every { sessionTokenRepository.deleteExpiredBefore(now) } returns 42
        val cutoff = slot<Instant>()

        // When
        val count = reaper.reap()

        // Then reap returns the count and passed clock.now() to the repository
        assertEquals(42, count)
        verify { sessionTokenRepository.deleteExpiredBefore(capture(cutoff)) }
        assertEquals(now, cutoff.captured)
    }
}
