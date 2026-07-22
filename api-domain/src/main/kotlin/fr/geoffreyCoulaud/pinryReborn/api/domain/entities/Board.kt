package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import java.time.Instant
import java.util.UUID

data class Board(
    override val id: UUID,
    val author: User,
    val name: String,
    val description: String,
    val softDeletedAt: Instant? = null,
    // Nullable: null means "not read from persistence". See User.createdAt for the rationale.
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) : Identifiable
