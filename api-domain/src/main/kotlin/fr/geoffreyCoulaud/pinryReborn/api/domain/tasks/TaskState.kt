package fr.geoffreyCoulaud.pinryReborn.api.domain.tasks

enum class TaskState {
    PENDING, RUNNING, SUCCEEDED, DEAD, CANCELLED,
    ;

    /**
     * True while the queue still owes this task an attempt, lease expiry included: an expired lease
     * leaves a `RUNNING` row the reaper hands back. Every other state has settled.
     */
    val isLiveAttempt: Boolean get() = this == PENDING || this == RUNNING
}
