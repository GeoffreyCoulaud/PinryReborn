package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

import java.time.Instant
import java.util.*

data class PinOutputDto(
    val id: UUID,
    val authorId: UUID,
    val sourceContextUrl: String,
    val sourceMediaUrl: String?,
    val description: String,
    val tags: List<TagOutputDto>,
    val boards: List<BoardRefDto>,
    val softDeletedAt: Instant? = null,
)
