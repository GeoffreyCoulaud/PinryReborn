package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ReauthenticationError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.AccountDeletionTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.EnqueueTask
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID.randomUUID

class AccountDeleterTest : BaseTest() {
    private val reauth = mockk<Reauthenticator>(relaxed = true)
    private val users = mockk<UserRepositoryInterface>(relaxed = true)
    private val sessionRevoker = mockk<SessionRevoker>(relaxed = true)
    private val enqueue = mockk<EnqueueTask>(relaxed = true)
    private val tx = mockk<TransactionRunner>()
    private val deleter = AccountDeleter(reauth, users, sessionRevoker, enqueue, tx)
    private val user = User(id = randomUUID(), name = "u", createdAt = Instant.now())

    @Test
    fun `Given a valid factor, Then it tombstones, revokes and enqueues`() {
        // Given
        every { tx.inTransaction(any<() -> Any?>()) } answers { (firstArg<() -> Any?>())() }
        // When
        deleter.requestDeletion(user, "secret")
        // Then
        verify { reauth.reauthenticate(user, "secret") }
        verifyOrder {
            users.markPendingDeletion(user)
            sessionRevoker.revokeAll(user)
            enqueue.enqueue(
                kind = AccountDeletionTask.KIND,
                payload = user.id.toString(),
                maxAttempts = AccountDeletionTask.MAX_ATTEMPTS,
                dedupKey = "${AccountDeletionTask.KIND}:${user.id}",
            )
        }
    }

    @Test
    fun `Given a failed step-up, Then nothing happens`() {
        // Given
        every { reauth.reauthenticate(user, "bad") } throws ReauthenticationError()
        // When / Then
        assertThrows<ReauthenticationError> { deleter.requestDeletion(user, "bad") }
        verify(exactly = 0) { users.markPendingDeletion(any()) }
    }
}
