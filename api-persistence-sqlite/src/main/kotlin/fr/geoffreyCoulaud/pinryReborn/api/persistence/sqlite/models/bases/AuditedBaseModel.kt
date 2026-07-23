package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases

import io.ebean.annotation.WhenCreated
import io.ebean.annotation.WhenModified
import jakarta.persistence.MappedSuperclass
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID

/**
 * Row audit columns owned by persistence, for entities whose domain counterpart carries no
 * timestamps at all.
 *
 * An entity whose domain type does expose a creation or update instant must NOT extend this:
 * @WhenCreated overwrites unconditionally on insert (GeneratedInsertJavaTime returns the clock
 * regardless of the value already on the bean), so the domain value would be silently discarded.
 * Those entities declare their own columns and let their mapper round-trip the domain value.
 */
@MappedSuperclass
class AuditedBaseModel(
    id: UUID = randomUUID(),
) : BaseModel(id = id) {
    @WhenCreated
    lateinit var whenCreated: Instant

    @WhenModified
    lateinit var whenModified: Instant
}
