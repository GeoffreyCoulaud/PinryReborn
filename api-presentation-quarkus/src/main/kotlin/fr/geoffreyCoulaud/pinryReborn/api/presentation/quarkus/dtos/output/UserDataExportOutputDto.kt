package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

import java.time.Instant
import java.util.UUID

/**
 * Size and media type travel in the representation, not only in the download headers, so a client
 * can announce e.g. "3.2 GB ZIP archive" before the user commits to downloading it (spec
 * `docs/specs/2026-07-22-user-data-export.md` §7).
 */
data class UserDataExportOutputDto(
    val id: UUID,
    val state: String,
    val requestedAt: Instant,
    val completedAt: Instant?,
    val expiresAt: Instant?,
    val byteSize: Long?,
    val mediaType: String?,
    val sha256: String?,
    val failureCode: String?,
    val formatVersion: Int,
)
