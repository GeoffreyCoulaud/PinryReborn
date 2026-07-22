package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import java.time.Instant
import java.util.UUID

data class User(
    override val id: UUID,
    val name: String,
    val softDeleted: Boolean = false,
    // Nullable: null means "not read from persistence" (e.g. a freshly-constructed, unsaved
    // entity). A non-null default would need a clock in the domain; a non-null field would force
    // every existing construction site to invent a timestamp.
    val createdAt: Instant? = null,
) : Identifiable
