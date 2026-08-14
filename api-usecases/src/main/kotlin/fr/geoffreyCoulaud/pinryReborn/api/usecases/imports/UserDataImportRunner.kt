package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImportIssue
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportIssueKind
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveBoundExceededException
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveLine
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveSource
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportIssueRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.UserDataExportRequester
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.exceptions.PermanentTaskException
import java.io.IOException
import java.util.UUID

/**
 * Replays an import archive into its owner's account (spec section 8), steps 1 to 5. Nothing that
 * already exists is modified: a conflict is a skip. Not `@ApplicationScoped`: two bounds have no producer.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class UserDataImportRunner(
    private val importRepository: UserDataImportRepositoryInterface,
    private val issueRepository: UserDataImportIssueRepositoryInterface,
    private val userRepository: UserRepositoryInterface,
    private val tagRepository: TagRepositoryInterface,
    private val boardRepository: BoardRepositoryInterface,
    private val archiveStore: ImportArchiveStore,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
    private val maxMetadataBytes: Long,
    private val leaseRenewalLines: Int,
) {
    /**
     * The `account.import` task's entry point. A row that is neither `PENDING` nor `RUNNING` is left
     * alone: it was cancelled, swept or already finished, and running it would resurrect it.
     */
    fun run(importId: UUID, renewLease: () -> Unit) {
        val userDataImport = importRepository.findById(importId)?.takeIf { it.state.isRunnable() } ?: return
        val user = requireUser(userDataImport)
        val runToken = UUID.randomUUID()
        val claimed = claim(userDataImport, runToken)
        walkArchive(project(claimed, runToken), claimed, user, renewLease)
    }

    private fun UserDataImportState.isRunnable(): Boolean =
        this == UserDataImportState.PENDING || this == UserDataImportState.RUNNING

    private fun requireUser(userDataImport: UserDataImport): User =
        userRepository.findUserById(userDataImport.userId)
            ?: markFailed(userDataImport, USER_GONE, "the account no longer exists")

    /** Step 2: one transaction writes the fence token, the state and the first attempt's instant. */
    private fun claim(userDataImport: UserDataImport, runToken: UUID): UserDataImport =
        transactionRunner.inTransaction {
            importRepository.save(
                userDataImport.copy(
                    state = UserDataImportState.RUNNING,
                    runToken = runToken,
                    startedAt = userDataImport.startedAt ?: clock.now(),
                ),
            )
        }

    /** The one validation site of spec section 5: only the storage key can be absent from a claimed row. */
    private fun project(claimed: UserDataImport, runToken: UUID): RunnableImport =
        RunnableImport(
            importId = claimed.id,
            userId = claimed.userId,
            storageKey =
                claimed.storageKey ?: markFailed(claimed, ARCHIVE_UNREADABLE, "the import names no archive"),
            runToken = runToken,
        )

    private fun walkArchive(
        runnable: RunnableImport,
        claimed: UserDataImport,
        user: User,
        renewLease: () -> Unit,
    ) {
        readingArchive(claimed) { archiveStore.open(runnable.storageKey) }.use { source ->
            val opened = recordManifest(source, claimed)
            val clamp = ImportInstantClamp(user.createdAt, clock.now())
            walkBoards(source, user, clamp, walkTags(source, user, clamp, opened, renewLease), renewLease)
        }
    }

    /**
     * Every way an archive refuses to be read is the same answer: the bytes will not change, so no
     * attempt is spent on them. A bound exceeded is not an [IOException], deliberately.
     */
    private fun <T> readingArchive(claimed: UserDataImport, read: () -> T): T =
        try {
            read()
        } catch (error: IOException) {
            markFailed(claimed, ARCHIVE_UNREADABLE, "the archive could not be read: $error")
        } catch (error: ArchiveBoundExceededException) {
            markFailed(claimed, ARCHIVE_UNREADABLE, "the archive read past its bound: $error")
        }

    /** Step 3. The count is display only, so a manifest disagreeing with the real line count is no error. */
    private fun recordManifest(source: ArchiveSource, claimed: UserDataImport): UserDataImport {
        val manifest =
            readingArchive(claimed) { source.readJson(MANIFEST_ENTRY, ImportedManifest::class.java, maxMetadataBytes) }
                ?: markFailed(claimed, MANIFEST_MISSING, "the archive carries no manifest")
        if (manifest.formatVersion != UserDataExportRequester.EXPORT_FORMAT_VERSION) {
            markFailed(claimed, UNSUPPORTED_FORMAT_VERSION, "format version ${manifest.formatVersion}")
        }
        return importRepository.save(
            claimed.copy(formatVersion = manifest.formatVersion, announcedPins = manifest.counts?.pins),
        )
    }

    private fun markFailed(userDataImport: UserDataImport, failureCode: String, reason: String): Nothing {
        importRepository.save(userDataImport.copy(state = UserDataImportState.FAILED, failureCode = failureCode))
        throw PermanentTaskException(reason)
    }

    private fun <T : Any> walkLines(
        source: ArchiveSource,
        name: String,
        type: Class<T>,
        renewLease: () -> Unit,
        importLine: (ArchiveLine<T>) -> Unit,
    ) {
        source.readJsonLines(name, type) { lines ->
            lines.forEach { line ->
                // Numbering counts the lines the reader skipped, so this fires on file position, not on
                // how many lines happened to parse.
                if (line.line % leaseRenewalLines == 0) renewLease()
                importLine(line)
            }
        }
    }

    /** Step 4. One row write per walk, not one per line: the tally is what a crash may cost. */
    private fun walkTags(
        source: ArchiveSource,
        user: User,
        clamp: ImportInstantClamp,
        current: UserDataImport,
        renewLease: () -> Unit,
    ): UserDataImport {
        val tally = MetadataTally(current.id)
        walkLines(source, TAGS_ENTRY, ImportedTag::class.java, renewLease) { importTag(it, user, clamp, tally) }
        return importRepository.save(
            current.copy(
                createdTags = current.createdTags + tally.created,
                skippedTags = current.skippedTags + tally.skipped,
                issueCount = current.issueCount + tally.issues,
            ),
        )
    }

    private fun importTag(
        line: ArchiveLine<ImportedTag>,
        user: User,
        clamp: ImportInstantClamp,
        tally: MetadataTally,
    ) {
        val tag = line.value
        val fault = tag?.let { ImportFieldBounds.nameFault(it.name) }
        when {
            tag == null -> record(tally, UserDataImportIssueKind.LINE_MALFORMED, line.line, null, line.failure)
            fault != null -> record(tally, UserDataImportIssueKind.FIELD_INVALID, line.line, tag.name, fault)
            tagRepository.findUserTagByName(user, tag.name) != null -> tally.skipped++
            else -> createTag(user, tag, clamp, tally)
        }
    }

    private fun createTag(user: User, tag: ImportedTag, clamp: ImportInstantClamp, tally: MetadataTally) {
        tagRepository.saveTag(
            Tag(id = UUID.randomUUID(), author = user, name = tag.name, createdAt = clamp.clamp(tag.createdAt)),
        )
        tally.created++
    }

    /** Step 5, counted apart from the tags so a merge can tell "nothing to do" from "did nothing". */
    private fun walkBoards(
        source: ArchiveSource,
        user: User,
        clamp: ImportInstantClamp,
        current: UserDataImport,
        renewLease: () -> Unit,
    ): UserDataImport {
        val tally = MetadataTally(current.id)
        walkLines(source, BOARDS_ENTRY, ImportedBoard::class.java, renewLease) { importBoard(it, user, clamp, tally) }
        return importRepository.save(
            current.copy(
                createdBoards = current.createdBoards + tally.created,
                skippedBoards = current.skippedBoards + tally.skipped,
                issueCount = current.issueCount + tally.issues,
            ),
        )
    }

    private fun importBoard(
        line: ArchiveLine<ImportedBoard>,
        user: User,
        clamp: ImportInstantClamp,
        tally: MetadataTally,
    ) {
        val board = line.value
        val fault = board?.let { boardFault(it) }
        when {
            board == null -> record(tally, UserDataImportIssueKind.LINE_MALFORMED, line.line, null, line.failure)
            fault != null -> record(tally, UserDataImportIssueKind.FIELD_INVALID, line.line, board.name, fault)
            else -> importNamedBoard(user, board, clamp, line.line, tally)
        }
    }

    private fun boardFault(board: ImportedBoard): String? =
        ImportFieldBounds.nameFault(board.name) ?: ImportFieldBounds.descriptionFault(board.description)

    /**
     * A board that already exists is left untouched whatever its state (spec section 8): only a board
     * this import creates carries the archive's description, timestamps and recycled state.
     */
    private fun importNamedBoard(
        user: User,
        board: ImportedBoard,
        clamp: ImportInstantClamp,
        line: Int,
        tally: MetadataTally,
    ) {
        val existing = boardRepository.findBoardForUserByName(user, board.name)
        if (existing == null) {
            createBoard(user, board, clamp, tally)
            return
        }
        tally.skipped++
        // Nothing is restored and nothing is renamed, so the holder of the name is named instead.
        if (existing.softDeletedAt != null) {
            record(tally, UserDataImportIssueKind.NAME_TAKEN_BY_RECYCLED, line, board.name, null)
        }
    }

    private fun createBoard(user: User, board: ImportedBoard, clamp: ImportInstantClamp, tally: MetadataTally) {
        val createdAt = clamp.clamp(board.createdAt)
        boardRepository.saveBoard(
            Board(
                id = UUID.randomUUID(),
                author = user,
                name = board.name,
                description = board.description,
                createdAt = createdAt,
                updatedAt = clamp.clampUpdate(board.updatedAt, createdAt),
                softDeletedAt = board.deletedAt?.let { clamp.clamp(it) },
            ),
        )
        tally.created++
    }

    private fun record(
        tally: MetadataTally,
        kind: UserDataImportIssueKind,
        line: Int,
        subject: String?,
        detail: String?,
    ) {
        issueRepository.save(
            UserDataImportIssue(
                id = UUID.randomUUID(),
                importId = tally.importId,
                kind = kind,
                line = line,
                subject = subject?.take(ISSUE_TEXT_LIMIT),
                detail = detail?.take(ISSUE_TEXT_LIMIT),
            ),
        )
        tally.issues++
    }

    /** What one metadata walk accumulates before its single row write. */
    private class MetadataTally(val importId: UUID) {
        var created = 0
        var skipped = 0
        var issues = 0
    }

    private companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val TAGS_ENTRY = "tags.jsonl"
        const val BOARDS_ENTRY = "boards.jsonl"
        const val ISSUE_TEXT_LIMIT = 200
        const val USER_GONE = "USER_GONE"
        const val ARCHIVE_UNREADABLE = "ARCHIVE_UNREADABLE"
        const val MANIFEST_MISSING = "MANIFEST_MISSING"
        const val UNSUPPORTED_FORMAT_VERSION = "UNSUPPORTED_FORMAT_VERSION"
    }
}
