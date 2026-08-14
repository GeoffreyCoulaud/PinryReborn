package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.PaginationOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.UserDataImportListOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.UserDataImportOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.CursorMapper.toDto

// The issues have their own mapper object: both pages erase to one `toDto(Page)` JVM signature.
object UserDataImportDtoMapper {
    fun UserDataImport.toDto() = UserDataImportOutputDto(
        id = id,
        state = state.name,
        requestedAt = requestedAt,
        uploadedBytes = uploadedBytes,
        byteSize = byteSize,
        archiveCompletedAt = archiveCompletedAt,
        startedAt = startedAt,
        completedAt = completedAt,
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

    fun Page<UserDataImport>.toDto() = UserDataImportListOutputDto(
        imports = items.map { it.toDto() },
        pagination = PaginationOutputDto(
            previousCursor = previousCursor?.toDto(),
            nextCursor = nextCursor?.toDto(),
        ),
    )
}
