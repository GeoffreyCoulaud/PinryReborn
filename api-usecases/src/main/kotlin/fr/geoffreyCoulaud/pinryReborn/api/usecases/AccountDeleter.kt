package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.AccountDeletionTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.EnqueueTask
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class AccountDeleter(
    private val reauthenticator: Reauthenticator,
    private val userRepository: UserRepositoryInterface,
    private val sessionRevoker: SessionRevoker,
    private val enqueueTask: EnqueueTask,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    fun requestDeletion(user: User, factor: String) {
        reauthenticator.reauthenticate(user, factor)
        transactionRunner.inTransaction {
            userRepository.markPendingDeletion(user, clock.now())
            sessionRevoker.revokeAll(user)
            enqueueTask.enqueue(
                kind = AccountDeletionTask.KIND,
                payload = user.id.toString(),
                maxAttempts = AccountDeletionTask.MAX_ATTEMPTS,
                dedupKey = "${AccountDeletionTask.KIND}:${user.id}",
            )
        }
    }
}
