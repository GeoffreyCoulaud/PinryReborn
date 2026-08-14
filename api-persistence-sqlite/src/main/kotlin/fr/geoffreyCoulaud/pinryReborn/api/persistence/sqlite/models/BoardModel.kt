package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.AuthoredBaseModel
import io.ebean.annotation.Index
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "boards")
// A board name is an identity per author. Covers every row, so the recycle bin holds its names; only
// `definition` carries `collate nocase`, and `unique = true` is a silent no-op on SQLite.
@Index(
    name = "ix_boards_author_name_nocase",
    definition = "create unique index ix_boards_author_name_nocase on boards (author_id, name collate nocase)",
)
@Suppress("LongParameterList") // Ebean entity: every parameter is a persisted column.
class BoardModel(
    id: UUID,
    author: UserModel,
    var name: String,
    var description: String,
    createdAt: Instant,
    // Written by the mapper from the domain entity, never generated. See AuthoredBaseModel.
    @Column(name = "when_modified") var updatedAt: Instant,
    override var softDeletedAt: Instant? = null,
) : AuthoredBaseModel(id = id, author = author, createdAt = createdAt),
    SoftDeletableModel
