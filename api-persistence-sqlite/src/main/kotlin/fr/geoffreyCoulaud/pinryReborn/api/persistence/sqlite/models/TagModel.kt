package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.AuthoredBaseModel
import io.ebean.annotation.Index
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "tags")
// A tag name is an identity per author. Only `definition` carries `collate nocase`, and
// `unique = true` is a silent no-op on SQLite.
@Index(
    name = "ix_tags_author_name_nocase",
    definition = "create unique index ix_tags_author_name_nocase on tags (author_id, name collate nocase)",
)
class TagModel(
    id: UUID,
    author: UserModel,
    val name: String,
    createdAt: Instant,
) : AuthoredBaseModel(id = id, author = author, createdAt = createdAt)
