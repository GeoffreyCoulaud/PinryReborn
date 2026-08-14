package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImportIssue
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportIssueKind
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportIssueRepositoryInterface
import java.util.UUID
import java.util.UUID.randomUUID

/**
 * Writes one import's issue rows under `imports.report_detail_limit` (spec section 9): past it only the
 * row's `issueCount` climbs and [truncated] flips. Seeded from the row, so a retry does not start over.
 */
internal class ImportIssueRecorder(
    private val issueRepository: UserDataImportIssueRepositoryInterface,
    private val importId: UUID,
    private val limit: Int,
    storedAlready: Int,
    truncatedAlready: Boolean,
) {
    private var stored = storedAlready

    var truncated = truncatedAlready
        private set

    /** [subject] and [detail] are cut, so a hostile line cannot make the report itself the payload. */
    fun record(kind: UserDataImportIssueKind, line: Int, subject: String?, detail: String?) {
        if (stored >= limit) {
            truncated = true
            return
        }
        issueRepository.save(
            UserDataImportIssue(
                id = randomUUID(),
                importId = importId,
                kind = kind,
                line = line,
                subject = subject?.take(TEXT_LIMIT),
                detail = detail?.take(TEXT_LIMIT),
            ),
        )
        stored++
    }

    private companion object {
        const val TEXT_LIMIT = 200
    }
}
