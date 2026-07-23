package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.AuthoredBaseModel
import io.ebean.annotation.WhenModified
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "tags")
class TagModel(
    id: UUID,
    author: UserModel,
    val name: String,
    createdAt: Instant,
) : AuthoredBaseModel(id = id, author = author, createdAt = createdAt) {
    // Persistence-only audit column: Tag exposes no update instant, so nothing maps this to the
    // domain. Kept rather than dropped because removing it would cost a migration for no gain.
    @WhenModified
    lateinit var whenModified: Instant
}
