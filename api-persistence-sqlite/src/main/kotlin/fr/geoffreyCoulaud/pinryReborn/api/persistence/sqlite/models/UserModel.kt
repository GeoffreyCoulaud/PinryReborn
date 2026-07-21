package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.BaseModel
import io.ebean.annotation.SoftDelete
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "users")
class UserModel(
    id: UUID,
    var name: String,
) : BaseModel(id = id) {
    @SoftDelete
    var deleted: Boolean = false
}
