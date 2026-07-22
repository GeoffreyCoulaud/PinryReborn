package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserDataExportModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.ModelCursor

object UserDataExportModelMapper {
    // `user` is passed in already resolved by the repository (see UserDataExportRepository.resolveUser):
    // the mapper itself never queries.
    fun UserDataExport.toModel(user: UserModel): UserDataExportModel =
        UserDataExportModel(
            id = id,
            user = user,
            state = state.name,
            formatVersion = formatVersion,
            requestedAt = requestedAt,
            taskId = taskId,
            completedAt = completedAt,
            expiresAt = expiresAt,
            storageKey = storageKey,
            byteSize = byteSize,
            sha256 = sha256,
            mediaType = mediaType,
            fileExtension = fileExtension,
            failureCode = failureCode,
        )

    // Reads ONLY `user.id`. An export row outlives its owner's soft-delete tombstone (the account
    // cleaner reaps exports after the user), so touching any other field of `model.user` here
    // (e.g. `.name`) would lazily load the tombstoned UserModel and crash.
    fun UserDataExportModel.toDomain(): UserDataExport =
        UserDataExport(
            id = id,
            userId = user.id,
            state = UserDataExportState.valueOf(state),
            formatVersion = formatVersion,
            requestedAt = requestedAt,
            taskId = taskId,
            completedAt = completedAt,
            expiresAt = expiresAt,
            storageKey = storageKey,
            byteSize = byteSize,
            sha256 = sha256,
            mediaType = mediaType,
            fileExtension = fileExtension,
            failureCode = failureCode,
        )

    fun ModelCursor<UserDataExportModel>.toDomain(): Cursor =
        Cursor(pivotId = this.pivot.id, direction = this.direction)
}
