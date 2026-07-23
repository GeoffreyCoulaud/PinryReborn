package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.PaginationOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.UserDataExportListOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.UserDataExportOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.CursorMapper.toDto

object UserDataExportDtoMapper {
    fun UserDataExport.toDto() = UserDataExportOutputDto(
        id = id,
        state = state.name,
        requestedAt = requestedAt,
        completedAt = completedAt,
        expiresAt = expiresAt,
        byteSize = byteSize,
        mediaType = mediaType,
        sha256 = sha256,
        failureCode = failureCode,
        formatVersion = formatVersion,
    )

    fun Page<UserDataExport>.toDto() = UserDataExportListOutputDto(
        exports = items.map { it.toDto() },
        pagination = PaginationOutputDto(
            previousCursor = previousCursor?.toDto(),
            nextCursor = nextCursor?.toDto(),
        ),
    )
}
