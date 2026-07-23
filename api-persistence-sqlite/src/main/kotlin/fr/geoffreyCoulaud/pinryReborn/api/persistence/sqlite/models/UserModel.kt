package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.BaseModel
import io.ebean.annotation.SoftDelete
import io.ebean.annotation.WhenModified
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class UserModel(
    id: UUID,
    var name: String,
    // Written by the mapper from the domain entity, never generated. See AuthoredBaseModel.
    @Column(name = "when_created") var createdAt: Instant,
) : BaseModel(id = id) {
    @SoftDelete
    var deleted: Boolean = false

    // Persistence-only audit column: User exposes no update instant, so nothing maps this to the
    // domain. Kept rather than dropped because it records when the row was tombstoned.
    @WhenModified
    lateinit var whenModified: Instant
}
