package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.BaseModel
import io.ebean.annotation.Index
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "session_tokens")
// Targets the expired-token garbage collection sweep: `deleteExpiredBefore` filters WHERE expires_at < ?, which is a
// full scan on a growing table without this index (spec 2026-07-27-periodic-gc.md section 11, D6).
@Index(columnNames = ["expires_at"])
class SessionTokenModel(
    id: UUID,
    @ManyToOne var user: UserModel,
    @Column(unique = true) var tokenHash: String,
    var expiresAt: Instant,
    var persistent: Boolean,
    // Reuses the historical `when_created` column: the property is now mapper-written from the
    // domain `createdAt` the use case stamps, no longer auto-stamped (D19).
    @Column(name = "when_created") var createdAt: Instant,
) : BaseModel(id = id)
