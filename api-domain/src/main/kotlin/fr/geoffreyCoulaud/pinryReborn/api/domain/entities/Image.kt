package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import java.time.Instant
import java.util.UUID

data class Image(
    override val id: UUID,
    val pinId: UUID,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val animated: Boolean,
    val byteSize: Long,
    val contentHash: String,
    val storageKey: String,
    val createdAt: Instant,
) : Identifiable
