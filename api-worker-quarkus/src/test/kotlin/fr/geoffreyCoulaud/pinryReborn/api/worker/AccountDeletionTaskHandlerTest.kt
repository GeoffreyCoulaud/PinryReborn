package fr.geoffreyCoulaud.pinryReborn.api.worker

import fr.geoffreyCoulaud.pinryReborn.api.usecases.AccountDeletionCleaner
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.AccountDeletionTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class AccountDeletionTaskHandlerTest {
    private val accountDeletionCleaner: AccountDeletionCleaner = mockk(relaxed = true)
    private val handler = AccountDeletionTaskHandler(accountDeletionCleaner)

    @Test fun `Given the handler, Then its kind is account delete`() {
        assertEquals(AccountDeletionTask.KIND, handler.kind)
    }

    @Test fun `Given a userId payload, Then it delegates to the cleaner`() {
        val userId = randomUUID()
        every { accountDeletionCleaner.deleteAccountData(userId) } returns Unit
        handler.handle(userId.toString(), TaskContext(1, AccountDeletionTask.MAX_ATTEMPTS))
        verify { accountDeletionCleaner.deleteAccountData(userId) }
    }
}
