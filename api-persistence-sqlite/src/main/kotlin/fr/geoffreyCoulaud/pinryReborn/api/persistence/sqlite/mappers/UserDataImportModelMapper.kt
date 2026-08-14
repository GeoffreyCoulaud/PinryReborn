package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserDataImportModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.ModelCursor

object UserDataImportModelMapper {
    // `user` arrives already resolved by the repository, which is the only place that decides whether
    // an active account is required; the mapper itself never queries.
    fun UserDataImport.toModel(user: UserModel): UserDataImportModel =
        UserDataImportModel(
            id = id,
            user = user,
            state = state.name,
            requestedAt = requestedAt,
            taskId = taskId,
            runToken = runToken,
            uploadedBytes = uploadedBytes,
            lastUploadActivityAt = lastUploadActivityAt,
            archiveCompletedAt = archiveCompletedAt,
            startedAt = startedAt,
            completedAt = completedAt,
            storageKey = storageKey,
            byteSize = byteSize,
            formatVersion = formatVersion,
            announcedPins = announcedPins,
            processedPins = processedPins,
            createdPins = createdPins,
            skippedPins = skippedPins,
            createdBoards = createdBoards,
            skippedBoards = skippedBoards,
            createdTags = createdTags,
            skippedTags = skippedTags,
            issueCount = issueCount,
            issueDetailTruncated = issueDetailTruncated,
            failureCode = failureCode,
        )

    // Reads ONLY `user.id`, as the export mapper does: an import row outlives its owner's tombstone,
    // so touching any other field would lazily load a UserModel that is no longer active.
    fun UserDataImportModel.toDomain(): UserDataImport =
        UserDataImport(
            id = id,
            userId = user.id,
            state = UserDataImportState.valueOf(state),
            requestedAt = requestedAt,
            taskId = taskId,
            runToken = runToken,
            uploadedBytes = uploadedBytes,
            lastUploadActivityAt = lastUploadActivityAt,
            archiveCompletedAt = archiveCompletedAt,
            startedAt = startedAt,
            completedAt = completedAt,
            storageKey = storageKey,
            byteSize = byteSize,
            formatVersion = formatVersion,
            announcedPins = announcedPins,
            processedPins = processedPins,
            createdPins = createdPins,
            skippedPins = skippedPins,
            createdBoards = createdBoards,
            skippedBoards = skippedBoards,
            createdTags = createdTags,
            skippedTags = skippedTags,
            issueCount = issueCount,
            issueDetailTruncated = issueDetailTruncated,
            failureCode = failureCode,
        )

    fun ModelCursor<UserDataImportModel>.toDomain(): Cursor =
        Cursor(pivotId = this.pivot.id, direction = this.direction)
}
