package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import java.time.Instant
import java.util.UUID

/**
 * One import request and its running counters, which increment and are never assigned, so a resumed
 * attempt adds to them. [runToken] fences the walk: a per-pin transaction proceeds only while its own.
 */
@Suppress("LongParameterList") // One row of counters; splitting it would only move the arity around.
data class UserDataImport(
    override val id: UUID,
    val userId: UUID,
    val state: UserDataImportState,
    val requestedAt: Instant,
    val taskId: UUID? = null,
    val runToken: UUID? = null,
    val uploadedBytes: Long = 0,
    val lastUploadActivityAt: Instant? = null,
    val archiveCompletedAt: Instant? = null,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val storageKey: String? = null,
    val byteSize: Long? = null,
    val formatVersion: Int? = null,
    val announcedPins: Int? = null,
    val processedPins: Int = 0,
    val createdPins: Int = 0,
    val skippedPins: Int = 0,
    val createdBoards: Int = 0,
    val skippedBoards: Int = 0,
    val createdTags: Int = 0,
    val skippedTags: Int = 0,
    val issueCount: Int = 0,
    val issueDetailTruncated: Boolean = false,
    val failureCode: String? = null,
) : Identifiable
