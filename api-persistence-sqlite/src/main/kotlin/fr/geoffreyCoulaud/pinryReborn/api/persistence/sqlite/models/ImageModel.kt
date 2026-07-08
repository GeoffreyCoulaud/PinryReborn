package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "images")
class ImageModel(
    @Id var id: UUID,
    @Column(unique = true) var pinId: UUID,
    var mimeType: String,
    var width: Int,
    var height: Int,
    var byteSize: Long,
    var contentHash: String,
    var storageKey: String,
    var createdAt: Instant,
)
