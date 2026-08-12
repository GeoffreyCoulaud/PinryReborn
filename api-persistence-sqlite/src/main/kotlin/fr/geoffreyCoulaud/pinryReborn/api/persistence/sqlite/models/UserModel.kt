package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.BaseModel
import io.ebean.annotation.Index
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
// Already created by `1.2.sql:2`, declared here to model it; only `definition` carries `collate nocase`.
@Index(
    name = "ix_users_name_nocase",
    definition = "create unique index ix_users_name_nocase on users (name collate nocase)",
)
class UserModel(
    id: UUID,
    var name: String,
    // Written by the mapper from the domain entity, never generated. See AuthoredBaseModel.
    @Column(name = "when_created") var createdAt: Instant,
    override var softDeletedAt: Instant? = null,
) : BaseModel(id = id),
    SoftDeletableModel
