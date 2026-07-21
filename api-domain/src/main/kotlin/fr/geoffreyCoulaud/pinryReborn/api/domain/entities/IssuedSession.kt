package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import java.time.Instant

data class IssuedSession(
    val token: String,
    val expiresAt: Instant,
    val renewAfter: Instant,
)
