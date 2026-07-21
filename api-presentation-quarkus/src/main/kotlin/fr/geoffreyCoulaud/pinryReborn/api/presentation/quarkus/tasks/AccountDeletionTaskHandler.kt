package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.usecases.AccountDeletionCleaner
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.AccountDeletionTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskContext
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskHandler
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class AccountDeletionTaskHandler(
    private val accountDeletionCleaner: AccountDeletionCleaner,
) : TaskHandler {
    override val kind = AccountDeletionTask.KIND

    override fun handle(payload: String, context: TaskContext) {
        accountDeletionCleaner.deleteAccountData(UUID.fromString(payload))
    }
}
