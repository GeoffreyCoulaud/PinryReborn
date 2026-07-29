package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import java.time.Instant

data class HashedPassword(
    val hash: String,
    val algorithm: PasswordHashAlgorithm,
    val createdAt: Instant,
)
