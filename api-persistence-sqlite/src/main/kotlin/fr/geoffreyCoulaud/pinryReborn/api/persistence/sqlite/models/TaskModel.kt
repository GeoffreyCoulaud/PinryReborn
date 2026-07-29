package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.AuditedBaseModel
import io.ebean.annotation.Index
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "tasks")
// Targets the terminal-task garbage collection sweep: `deleteTerminalBefore` filters WHERE state IN (...) AND
// terminal_state_at < ?. `state` leads as the more selective predicate; `terminal_state_at` is the
// instant a task entered a terminal state, written explicitly on every terminal transition.
// Without it the sweep is a full scan over a table that accumulates terminal rows forever
// (spec 2026-07-27-periodic-gc.md section 11, D6).
@Index(columnNames = ["state", "terminal_state_at"])
class TaskModel
    @Suppress("LongParameterList")
    constructor(
        id: UUID,
        var kind: String,
        var payload: String,
        var state: String,
        var priority: Int,
        var availableAt: Instant,
        var attempts: Int,
        var maxAttempts: Int,
        var leaseId: String? = null,
        var leaseExpiresAt: Instant? = null,
        var cancelRequested: Boolean = false,
        var dedupKey: String? = null,
        var lastError: String? = null,
        var terminalStateAt: Instant? = null,
    ) : AuditedBaseModel(id = id) {
    @Version
    var version: Long = 0
}
