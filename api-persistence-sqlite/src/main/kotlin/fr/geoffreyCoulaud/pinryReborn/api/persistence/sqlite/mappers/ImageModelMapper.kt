package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.ImageModel

object ImageModelMapper {
    fun Image.toModel() = ImageModel(
        id = id, pinId = pinId, mimeType = mimeType, width = width, height = height, animated = animated,
        byteSize = byteSize, contentHash = contentHash, storageKey = storageKey, createdAt = createdAt,
    )

    fun ImageModel.toDomain() = Image(
        id = id, pinId = pinId, mimeType = mimeType, width = width, height = height, animated = animated,
        byteSize = byteSize, contentHash = contentHash, storageKey = storageKey, createdAt = createdAt,
    )
}
