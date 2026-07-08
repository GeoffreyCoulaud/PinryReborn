package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

import java.util.UUID

data class ImageOutputDto(
    val id: UUID,
    val pinId: UUID,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val url: String,
)
