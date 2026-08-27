package fr.geoffreyCoulaud.pinryReborn.api.domain.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class TaskStateTest {
    @Test
    fun `Given every state, Then the live attempts are the two the queue still owes its row`() {
        // Given / When
        val live = TaskState.entries.filter { it.isLiveAttempt }

        // Then: lease expiry included, since an expired lease is still a RUNNING row the queue
        // will hand back to a worker
        assertEquals(listOf(TaskState.PENDING, TaskState.RUNNING), live)
    }

    @Test
    fun `Given a settled state, Then it is not a live attempt`() {
        // Given / When / Then: a sweep reading this predicate as "dead or absent" would take a
        // SUCCEEDED or CANCELLED task for a live one and leave its row stuck for good
        assertFalse(TaskState.DEAD.isLiveAttempt)
        assertFalse(TaskState.SUCCEEDED.isLiveAttempt)
        assertFalse(TaskState.CANCELLED.isLiveAttempt)
    }
}
