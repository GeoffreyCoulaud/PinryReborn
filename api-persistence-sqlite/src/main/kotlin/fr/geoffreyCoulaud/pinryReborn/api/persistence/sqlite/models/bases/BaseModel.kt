package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases

import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import java.util.UUID
import java.util.UUID.randomUUID

@MappedSuperclass
@Suppress("AbstractClassCanBeConcreteClass") // Abstract by intent: a mapped-superclass, never instantiated directly.
abstract class BaseModel(
    @Id var id: UUID = randomUUID(),
)
