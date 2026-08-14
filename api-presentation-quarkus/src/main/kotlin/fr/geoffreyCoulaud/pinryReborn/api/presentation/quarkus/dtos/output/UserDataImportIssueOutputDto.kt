package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

import java.util.UUID

/** One line of the import report. [subject] and [detail] are stored already truncated (spec §5). */
data class UserDataImportIssueOutputDto(
    val id: UUID,
    val kind: String,
    val line: Int?,
    val subject: String?,
    val detail: String?,
)
