package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState

/**
 * The states a partial unique index selects, spelled as the column stores them.
 *
 * The index constrains those rows and no others, so a query that means the same rows names the same set or the
 * two answer different questions. `PartialUniqueIndexStatesTest` reads each set against the `where` clause of
 * the migration that created the index.
 */
internal object PartialUniqueIndexStates {
    /** `ux_tasks_dedup` (`1.3.sql:27`): a dedup key is unique among the tasks in these states. */
    val liveTaskStates: Set<String> = setOf(TaskState.PENDING.name, TaskState.RUNNING.name)

    /** `uq_user_data_exports_pending` (`1.11.sql:2`): a user has at most one export in these states. */
    val pendingExportStates: Set<String> = setOf(UserDataExportState.PENDING.name)
}
