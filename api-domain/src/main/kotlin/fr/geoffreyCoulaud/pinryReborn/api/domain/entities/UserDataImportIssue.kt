package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportIssueKind
import java.util.UUID

/**
 * One line of the import report. [subject] and [detail] are truncated before storage, so a hostile
 * archive line cannot make the report itself the payload.
 */
data class UserDataImportIssue(
    override val id: UUID,
    val importId: UUID,
    val kind: UserDataImportIssueKind,
    val line: Int?,
    val subject: String?,
    val detail: String?,
) : Identifiable
