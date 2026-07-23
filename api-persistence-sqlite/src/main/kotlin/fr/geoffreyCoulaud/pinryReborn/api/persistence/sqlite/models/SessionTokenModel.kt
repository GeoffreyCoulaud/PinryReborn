package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.AuditedBaseModel
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "session_tokens")
class SessionTokenModel(
    id: UUID,
    @ManyToOne var user: UserModel,
    @Column(unique = true) var tokenHash: String,
    var expiresAt: Instant,
    var persistent: Boolean,
) : AuditedBaseModel(id = id)
