package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

import java.time.Instant

data class ExistingSessionOutputDto(
    val expiresAt: Instant,
    val renewAfter: Instant,
    val persistent: Boolean,
)
