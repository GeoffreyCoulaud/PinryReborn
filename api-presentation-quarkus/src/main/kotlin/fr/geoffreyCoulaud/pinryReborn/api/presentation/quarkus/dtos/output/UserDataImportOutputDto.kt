package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

import java.time.Instant
import java.util.UUID

/**
 * The import row as the client reads it (spec §7). The two pin counters ship raw, with no progress
 * ratio: a server-side one would add two degenerate branches and publish nothing the client cannot compute.
 */
data class UserDataImportOutputDto(
    val id: UUID,
    val state: String,
    val requestedAt: Instant,
    val uploadedBytes: Long,
    val byteSize: Long?,
    val archiveCompletedAt: Instant?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val formatVersion: Int?,
    val announcedPins: Int?,
    val processedPins: Int,
    val createdPins: Int,
    val skippedPins: Int,
    val createdBoards: Int,
    val skippedBoards: Int,
    val createdTags: Int,
    val skippedTags: Int,
    val issueCount: Int,
    val issueDetailTruncated: Boolean,
    val failureCode: String?,
)
