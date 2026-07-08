package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.Task
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.TaskModel

object TaskModelMapper {
    fun TaskModel.toDomain(): Task =
        Task(
            id = id,
            kind = kind,
            payload = payload,
            state = TaskState.valueOf(state),
            priority = priority,
            availableAt = availableAt,
            attempts = attempts,
            maxAttempts = maxAttempts,
            leaseId = leaseId,
            leaseExpiresAt = leaseExpiresAt,
            cancelRequested = cancelRequested,
            dedupKey = dedupKey,
            lastError = lastError,
        )
}
