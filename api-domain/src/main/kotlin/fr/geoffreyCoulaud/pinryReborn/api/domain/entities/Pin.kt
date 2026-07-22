package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import java.time.Instant
import java.util.UUID

data class Pin(
    override val id: UUID,
    val author: User,
    val sourceContextUrl: String,
    val sourceMediaUrl: String?,
    val description: String,
    val tags: List<Tag>,
    val boards: List<Board>,
    val softDeletedAt: Instant? = null,
    val image: Image? = null,
    // Nullable: null means "not read from persistence". See User.createdAt for the rationale.
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) : Identifiable
