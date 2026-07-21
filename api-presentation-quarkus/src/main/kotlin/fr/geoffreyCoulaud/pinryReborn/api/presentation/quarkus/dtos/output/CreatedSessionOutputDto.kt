package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

import java.time.Instant

data class CreatedSessionOutputDto(
    val token: String,
    val expiresAt: Instant,
    val renewAfter: Instant,
)
