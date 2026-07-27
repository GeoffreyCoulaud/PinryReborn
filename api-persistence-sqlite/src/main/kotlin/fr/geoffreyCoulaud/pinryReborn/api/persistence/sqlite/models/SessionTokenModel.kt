package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.AuditedBaseModel
import io.ebean.annotation.Index
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "session_tokens")
// Targets the expired-token GC sweep: `deleteExpiredBefore` filters WHERE expires_at < ?, which is a
// full scan on a growing table without this index (spec 2026-07-27-periodic-gc.md section 11, D6).
@Index(columnNames = ["expires_at"])
class SessionTokenModel(
    id: UUID,
    @ManyToOne var user: UserModel,
    @Column(unique = true) var tokenHash: String,
    var expiresAt: Instant,
    var persistent: Boolean,
) : AuditedBaseModel(id = id)
