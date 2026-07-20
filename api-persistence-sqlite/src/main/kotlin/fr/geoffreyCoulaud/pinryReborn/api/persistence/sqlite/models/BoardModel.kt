package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.AuthoredBaseModel
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "boards")
class BoardModel(
    id: UUID,
    author: UserModel,
    var name: String,
    var description: String,
    var softDeletedAt: Instant? = null,
) : AuthoredBaseModel(id = id, author = author)
