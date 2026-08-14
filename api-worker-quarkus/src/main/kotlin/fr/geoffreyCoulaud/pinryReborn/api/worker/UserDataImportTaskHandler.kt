package fr.geoffreyCoulaud.pinryReborn.api.worker

import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.UserDataImportRunner
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskContext
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskHandler
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.UserDataImportTask
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.util.UUID

/** The `account.import` kind, delegated whole: every branch it triggers lives in the runner. */
@ApplicationScoped
class UserDataImportTaskHandler(
    private val runner: UserDataImportRunner,
    private val config: ImportsConfig,
) : TaskHandler {
    override val kind = UserDataImportTask.KIND

    /** Read per settlement rather than at construction, so a retuned floor reaches the tasks already queued. */
    override val retryFloor: Duration get() = config.retryFloor()

    override fun handle(payload: String, context: TaskContext) =
        runner.run(
            UUID.fromString(payload),
            isLastAttempt = context.attempt >= context.maxAttempts,
            renewLease = context.renewLease,
        )
}
