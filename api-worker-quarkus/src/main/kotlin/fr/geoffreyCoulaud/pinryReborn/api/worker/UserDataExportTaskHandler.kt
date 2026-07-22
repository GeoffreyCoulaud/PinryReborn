package fr.geoffreyCoulaud.pinryReborn.api.worker

import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.UserDataExportBuilder
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskContext
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskHandler
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.UserDataExportTask
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class UserDataExportTaskHandler(
    private val builder: UserDataExportBuilder,
) : TaskHandler {
    override val kind = UserDataExportTask.KIND

    override fun handle(payload: String, context: TaskContext) =
        builder.build(
            UUID.fromString(payload),
            isLastAttempt = context.attempt >= context.maxAttempts,
            renewLease = context.renewLease,
        )
}
