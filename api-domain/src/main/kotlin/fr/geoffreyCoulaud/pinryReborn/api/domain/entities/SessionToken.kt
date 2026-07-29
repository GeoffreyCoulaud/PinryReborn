package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import java.time.Instant
import java.util.UUID

data class SessionToken(
    override val id: UUID,
    val user: User,
    val expiresAt: Instant,
    val persistent: Boolean,
    val createdAt: Instant,
) : Identifiable
