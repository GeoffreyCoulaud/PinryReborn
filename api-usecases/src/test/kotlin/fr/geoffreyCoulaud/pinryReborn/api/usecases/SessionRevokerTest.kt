package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class SessionRevokerTest {
    private val repository = mockk<SessionTokenRepositoryInterface>(relaxed = true)
    private val revoker = SessionRevoker(repository)
    private val user = User(id = randomUUID(), name = "alice", createdAt = TestTime.now)

    @Test
    fun `Given a current token, Then revokeCurrent deletes it by id`() {
        val current = SessionToken(randomUUID(), user, TestTime.now, persistent = false, createdAt = TestTime.now)
        revoker.revokeCurrent(current)
        verify { repository.deleteById(current.id) }
    }

    @Test
    fun `Given a user, Then revokeAll deletes all their tokens`() {
        revoker.revokeAll(user)
        verify { repository.deleteAllForUser(user.id) }
    }
}
