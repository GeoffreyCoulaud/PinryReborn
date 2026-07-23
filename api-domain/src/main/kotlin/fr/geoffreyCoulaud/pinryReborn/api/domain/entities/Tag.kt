package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import java.time.Instant
import java.util.UUID

data class Tag(
    override val id: UUID,
    val author: User,
    val name: String,
    val createdAt: Instant,
) : Identifiable
