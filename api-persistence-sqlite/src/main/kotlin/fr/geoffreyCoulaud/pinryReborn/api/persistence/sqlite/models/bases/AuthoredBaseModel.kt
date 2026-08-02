package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserModel
import jakarta.persistence.Column
import jakarta.persistence.ManyToOne
import jakarta.persistence.MappedSuperclass
import java.time.Instant
import java.util.UUID

@MappedSuperclass
@Suppress("AbstractClassCanBeConcreteClass") // Abstract by intent: a mapped-superclass, never instantiated directly.
abstract class AuthoredBaseModel(
    id: UUID,
    @ManyToOne var author: UserModel,
    // Written by the mapper from the domain entity, never generated: the use case that creates the
    // entity owns the instant. The column keeps its historical name so the change costs no
    // migration.
    @Column(name = "when_created") var createdAt: Instant,
) : BaseModel(id = id)
