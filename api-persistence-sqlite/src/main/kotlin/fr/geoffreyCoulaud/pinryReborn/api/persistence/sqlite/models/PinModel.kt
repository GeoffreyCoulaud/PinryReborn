package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.AuthoredBaseModel
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "pins")
class PinModel(
    id: UUID,
    author: UserModel,
    var sourceContextUrl: String,
    var sourceMediaUrl: String?,
    var description: String,
    createdAt: Instant,
    // Written by the mapper from the domain entity, never generated. See AuthoredBaseModel.
    @Column(name = "when_modified") var updatedAt: Instant,
    override var softDeletedAt: Instant? = null,
) : AuthoredBaseModel(
        id = id,
        author = author,
        createdAt = createdAt,
    ),
    SoftDeletableModel
