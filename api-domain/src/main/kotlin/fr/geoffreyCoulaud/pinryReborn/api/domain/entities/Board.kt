package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import java.time.Instant
import java.util.UUID

data class Board(
    override val id: UUID,
    val author: User,
    val name: String,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val softDeletedAt: Instant? = null,
) : Identifiable
