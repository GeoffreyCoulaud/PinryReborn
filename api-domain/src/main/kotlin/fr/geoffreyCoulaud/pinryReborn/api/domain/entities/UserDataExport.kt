package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import java.time.Instant
import java.util.UUID

data class UserDataExport(
    override val id: UUID,
    val userId: UUID,
    val state: UserDataExportState,
    val formatVersion: Int,
    val requestedAt: Instant,
    val taskId: UUID? = null,
    val completedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val storageKey: String? = null,
    val byteSize: Long? = null,
    val sha256: String? = null,
    val mediaType: String? = null,
    val fileExtension: String? = null,
    val failureCode: String? = null,
) : Identifiable
