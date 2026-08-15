package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.boards.BoardNameAlreadyTakenException
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportIssueKind
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageProbe
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageProbeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooLargeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooManyPixelsException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ProbeResult
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveBoundExceededException
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveEntryUnreadableException
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveLine
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ArchiveSource
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportIssueRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.TagCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.deleteQuietly
import fr.geoffreyCoulaud.pinryReborn.api.usecases.discardQuietly
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.UserDataExportRequester
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.exceptions.PermanentTaskException
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID

/** Replays an import archive into its owner's account (spec section 8): a conflict is a skip. */
// Not `@ApplicationScoped`: `ImportProducers` builds it, since ARC resolves none of its six bounds.
// LongParameterList and TooManyFunctions: those bounds have no type to group them with the ports, and
// each step being its own named helper is what keeps every one of them under LongMethod.
@Suppress("LongParameterList", "TooManyFunctions")
class UserDataImportRunner(
    private val importRepository: UserDataImportRepositoryInterface,
    private val issueRepository: UserDataImportIssueRepositoryInterface,
    private val userRepository: UserRepositoryInterface,
    private val tagRepository: TagRepositoryInterface,
    private val boardRepository: BoardRepositoryInterface,
    private val pinRepository: PinRepositoryInterface,
    private val imageRepository: ImageRepositoryInterface,
    private val archiveStore: ImportArchiveStore,
    private val imageStore: ImageStore,
    private val imageProbe: ImageProbe,
    private val tagCreator: TagCreator,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
    private val maxMetadataBytes: Long,
    private val maxEntries: Int,
    private val maxImageBytes: Long,
    private val maxPixels: Long,
    private val leaseRenewalLines: Int,
    private val reportDetailLimit: Int,
) {
    /**
     * The `account.import` task's entry point. A row that is neither `PENDING` nor `RUNNING` is left
     * alone: it was cancelled, swept or already finished, and running it would resurrect it.
     */
    fun run(importId: UUID, isLastAttempt: Boolean, renewLease: () -> Unit) {
        val userDataImport = importRepository.findById(importId)?.takeIf { it.state.isRunnable() } ?: return
        val user = requireUser(userDataImport)
        val runToken = UUID.randomUUID()
        val claimed = claim(importId, runToken) ?: return
        replay(project(claimed, runToken), user, isLastAttempt, renewLease)
    }

    /**
     * Steps 3 to 8. An unenumerated throw marks the row only on the last attempt, and always rethrows so
     * the queue counts the attempt; a permanent refusal already marked it, which the fence reads.
     */
    @Suppress("TooGenericExceptionCaught")
    // Caught as broadly as `UserDataExportBuilder.stageOrFail` does: a row left RUNNING for ever holds
    // the account's only import slot, which outweighs anything this arm can catch by mistake.
    private fun replay(
        runnable: RunnableImport,
        user: User,
        isLastAttempt: Boolean,
        renewLease: () -> Unit,
    ) {
        try {
            walkArchive(runnable, user, renewLease)
            complete(runnable)
        } catch (error: Throwable) {
            if (isLastAttempt) advance(runnable) { failed(it, IMPORT_FAILED) }
            throw error
        } finally {
            releaseArchive(runnable)
        }
    }

    /** Step 7: `COMPLETED` lands only while the row still holds the run, never over a cancellation. */
    private fun complete(runnable: RunnableImport) {
        advance(runnable) { it.copy(state = UserDataImportState.COMPLETED, completedAt = clock.now()) }
    }

    /**
     * Step 8. The bytes go once the row is terminal or gone, and stay while it is `RUNNING`: a retry
     * resumes from the cursor, and a row a second runner has claimed is being read right now.
     */
    private fun releaseArchive(runnable: RunnableImport) {
        val current = importRepository.findById(runnable.importId)
        if (current == null || current.state.isTerminal) archiveStore.deleteQuietly(runnable.storageKey)
    }

    private fun UserDataImportState.isRunnable(): Boolean =
        this == UserDataImportState.PENDING || this == UserDataImportState.RUNNING

    /** Before the claim, so the fence is the state alone: no attempt holds this row yet. */
    private fun requireUser(userDataImport: UserDataImport): User =
        userRepository.findUserById(userDataImport.userId)
            ?: markFailed(userDataImport.id, USER_GONE, "the account no longer exists") { it.state.isRunnable() }

    /**
     * Step 2, on the row as it is now: null when it stopped being runnable while the account was looked
     * up, which a merge of the copy read before that lookup would have restored to RUNNING instead.
     */
    private fun claim(importId: UUID, runToken: UUID): UserDataImport? =
        fenced(importId, { it.state.isRunnable() }) {
            it.copy(
                state = UserDataImportState.RUNNING,
                runToken = runToken,
                startedAt = it.startedAt ?: clock.now(),
            )
        }

    /** The one validation site of spec section 5: only the storage key can be absent from a claimed row. */
    private fun project(claimed: UserDataImport, runToken: UUID): RunnableImport =
        RunnableImport(
            importId = claimed.id,
            userId = claimed.userId,
            storageKey =
                claimed.storageKey
                    ?: markFailed(claimed.id, ARCHIVE_UNREADABLE, "the import names no archive") {
                        it.holds(runToken)
                    },
            runToken = runToken,
        )

    private fun walkArchive(runnable: RunnableImport, user: User, renewLease: () -> Unit) {
        readingArchive(runnable) { archiveStore.open(runnable.storageKey) }.use { source ->
            // Read where the central directory already is, so it costs nothing: an archive past the
            // bound is refused before a walk has created anything, rather than after both of them.
            val entryNames = readingArchive(runnable) { source.entryNames(maxEntries) }
            val opened = recordManifest(source, runnable) ?: return
            walkContent(source, runnable, user, renewLease, recorderFor(opened), entryNames)
        }
    }

    /** Steps 4 to 6. A walk whose fenced write is refused ends the run there: the row is no longer ours. */
    private fun walkContent(
        source: ArchiveSource,
        runnable: RunnableImport,
        user: User,
        renewLease: () -> Unit,
        recorder: ImportIssueRecorder,
        entryNames: Set<String>,
    ) {
        val now = clock.now()
        val clamp = ImportInstantClamp(user.createdAt, now)
        walkTags(source, user, clamp, runnable, renewLease, recorder) ?: return
        val walked = walkBoards(source, user, clamp, runnable, renewLease, recorder) ?: return
        walkPins(PinWalk(source, runnable, user, clamp, now, entryNames, recorder, renewLease), walked)
    }

    /**
     * The fence at every row write the per-pin settlement does not own: re-read inside the transaction,
     * written only while the row still holds the run, since a blind merge restores state and token.
     */
    private fun advance(runnable: RunnableImport, update: (UserDataImport) -> UserDataImport): UserDataImport? =
        fenced(runnable.importId, { it.holds(runnable.runToken) }, update)

    /** Read and write in one transaction, on the row as it is now: no caller ever merges its own copy. */
    private fun fenced(
        importId: UUID,
        held: (UserDataImport) -> Boolean,
        update: (UserDataImport) -> UserDataImport,
    ): UserDataImport? = importRepository.saveFenced(transactionRunner, importId, held, update)

    /** The cap counts the rows already stored, which a lost counter write would over-report. */
    private fun recorderFor(opened: UserDataImport): ImportIssueRecorder =
        ImportIssueRecorder(
            issueRepository = issueRepository,
            importId = opened.id,
            limit = reportDetailLimit,
            storedAlready = issueRepository.countForImport(opened.id),
            truncatedAlready = opened.issueDetailTruncated,
        )

    /**
     * Every way an archive refuses to be read is the same answer: the bytes will not change, so no
     * attempt is spent on them. A bound exceeded is not an [IOException], deliberately.
     */
    private fun <T> readingArchive(runnable: RunnableImport, read: () -> T): T =
        try {
            read()
        } catch (error: IOException) {
            markFailed(runnable, ARCHIVE_UNREADABLE, "the archive could not be read: $error")
        } catch (error: ArchiveBoundExceededException) {
            markFailed(runnable, ARCHIVE_UNREADABLE, "the archive read past its bound: $error")
        }

    /** Step 3. The count is display only, so a manifest disagreeing with the real line count is no error. */
    private fun recordManifest(source: ArchiveSource, runnable: RunnableImport): UserDataImport? {
        val manifest =
            readingArchive(runnable) { source.readJson(MANIFEST_ENTRY, ImportedManifest::class.java, maxMetadataBytes) }
                ?: markFailed(runnable, MANIFEST_MISSING, "the archive carries no manifest")
        if (manifest.formatVersion != UserDataExportRequester.EXPORT_FORMAT_VERSION) {
            markFailed(runnable, UNSUPPORTED_FORMAT_VERSION, "format version ${manifest.formatVersion}")
        }
        return advance(runnable) {
            it.copy(formatVersion = manifest.formatVersion, announcedPins = manifest.counts?.pins)
        }
    }

    /** The same refusal, fenced on the run: every site holding the projection reads it from there. */
    private fun markFailed(runnable: RunnableImport, failureCode: String, reason: String): Nothing =
        markFailed(runnable.importId, failureCode, reason) { it.holds(runnable.runToken) }

    /** A refusal is a write like any other: on the row as it is now, and only while it is still ours. */
    private fun markFailed(
        importId: UUID,
        failureCode: String,
        reason: String,
        held: (UserDataImport) -> Boolean,
    ): Nothing {
        fenced(importId, held) { failed(it, failureCode) }
        throw PermanentTaskException(reason)
    }

    private fun failed(current: UserDataImport, failureCode: String): UserDataImport =
        current.copy(state = UserDataImportState.FAILED, failureCode = failureCode)

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
        runnable: RunnableImport,
        renewLease: () -> Unit,
        recorder: ImportIssueRecorder,
    ): UserDataImport? {
        val tally = MetadataTally(recorder)
        walkLines(source, TAGS_ENTRY, ImportedTag::class.java, renewLease) {
            rejecting(tally, it.line) { importTag(it, user, clamp, tally) }
        }
        return advance(runnable) {
            it.copy(
                createdTags = it.createdTags + tally.created,
                skippedTags = it.skippedTags + tally.skipped,
                issueCount = it.issueCount + tally.issues,
                issueDetailTruncated = recorder.truncated,
            )
        }
    }

    /**
     * `LINE_REJECTED` for a metadata line, as [importPin] has for a pin: a name a concurrent write took
     * between this walk's read and its own raises against a unique index, and costs its line only.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun rejecting(tally: MetadataTally, line: Int, importLine: () -> Unit) {
        try {
            importLine()
        } catch (error: RuntimeException) {
            record(tally, UserDataImportIssueKind.LINE_REJECTED, line, null, error.toString())
        } catch (error: BoardNameAlreadyTakenException) {
            record(tally, UserDataImportIssueKind.LINE_REJECTED, line, null, error.toString())
        }
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
            else -> resolveTag(user, tag, clamp, tally)
        }
    }

    /** Through the one resolver, so the pair the unique index needs is one transaction here too. */
    private fun resolveTag(user: User, tag: ImportedTag, clamp: ImportInstantClamp, tally: MetadataTally) {
        val resolved = tagCreator.resolve(name = tag.name, user = user, createdAt = clamp.clamp(tag.createdAt))
        if (resolved.created) tally.created++ else tally.skipped++
    }

    /** Step 5, counted apart from the tags so a merge can tell "nothing to do" from "did nothing". */
    private fun walkBoards(
        source: ArchiveSource,
        user: User,
        clamp: ImportInstantClamp,
        runnable: RunnableImport,
        renewLease: () -> Unit,
        recorder: ImportIssueRecorder,
    ): UserDataImport? {
        val tally = MetadataTally(recorder)
        walkLines(source, BOARDS_ENTRY, ImportedBoard::class.java, renewLease) {
            rejecting(tally, it.line) { importBoard(it, user, clamp, tally) }
        }
        return advance(runnable) {
            it.copy(
                createdBoards = it.createdBoards + tally.created,
                skippedBoards = it.skippedBoards + tally.skipped,
                issueCount = it.issueCount + tally.issues,
                issueDetailTruncated = recorder.truncated,
            )
        }
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
        // Nothing is restored and nothing is renamed, so the holder of the name is named instead. One
        // this walk recycled itself is excluded: the archive named it twice, which is not a conflict.
        if (existing.softDeletedAt != null && existing.id !in tally.recycled) {
            record(tally, UserDataImportIssueKind.NAME_TAKEN_BY_RECYCLED, line, board.name, null)
        }
    }

    private fun createBoard(user: User, board: ImportedBoard, clamp: ImportInstantClamp, tally: MetadataTally) {
        val createdAt = clamp.clamp(board.createdAt)
        val created =
            Board(
                id = randomUUID(),
                author = user,
                name = board.name,
                description = board.description,
                createdAt = createdAt,
                updatedAt = clamp.clampUpdate(board.updatedAt, createdAt),
                softDeletedAt = board.deletedAt?.let { clamp.clamp(it) },
            )
        boardRepository.saveBoard(created)
        if (created.softDeletedAt != null) tally.recycled += created.id
        tally.created++
    }

    private fun record(
        tally: MetadataTally,
        kind: UserDataImportIssueKind,
        line: Int,
        subject: String?,
        detail: String?,
    ) {
        tally.recorder.record(kind, line, subject, detail)
        tally.issues++
    }

    /**
     * Step 6, from the cursor. One transaction per line, so an interruption costs at most the pin it was
     * on, and a refused fence stops the walk rather than letting a second worker write beside the first.
     */
    private fun walkPins(walk: PinWalk, current: UserDataImport) {
        val cursor = current.processedPins
        var seen = 0
        var holding = true
        walk.source.readJsonLines(PINS_ENTRY, ImportedPin::class.java) { lines ->
            lines.takeWhile { holding }.forEach { line ->
                seen++
                walk.renewLease()
                if (seen > cursor) holding = importPin(walk, line)
            }
        }
    }

    /**
     * `LINE_REJECTED` makes "one bad entry never fails an import" structural: the archive's own
     * [ArchiveEntryUnreadableException] lands there too, while a write failure escapes and retries.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun importPin(walk: PinWalk, line: ArchiveLine<ImportedPin>): Boolean =
        try {
            settle(walk, line.line, outcomeFor(walk, line))
        } catch (error: RuntimeException) {
            rejected(walk, line.line, error)
        } catch (error: ArchiveEntryUnreadableException) {
            rejected(walk, line.line, error)
        }

    private fun rejected(walk: PinWalk, line: Int, error: Exception): Boolean =
        settle(walk, line, reported(UserDataImportIssueKind.LINE_REJECTED, null, error.toString()))

    private fun settle(walk: PinWalk, line: Int, outcome: PinOutcome): Boolean =
        when (val created = outcome.created) {
            null -> write(walk, line, outcome)
            else -> promoteAndWrite(walk, line, outcome, created)
        }

    /**
     * The bytes land before the row, as `SetPinImage` does, and a refusal undoes both halves: a promoted
     * object nothing points at is residue the sweep would have to reclaim.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun promoteAndWrite(walk: PinWalk, line: Int, outcome: PinOutcome, created: CreatedPin): Boolean =
        try {
            imageStore.promote(created.staged, created.image.storageKey)
            write(walk, line, outcome).also { held -> if (!held) compensate(created) }
        } catch (error: Exception) {
            compensate(created)
            throw error
        }

    private fun compensate(created: CreatedPin) {
        imageStore.discardQuietly(created.staged)
        imageStore.deleteQuietly(created.image.storageKey)
    }

    /** The same fence, settling one line: the issue rows and the cursor land in one transaction or none. */
    private fun write(walk: PinWalk, line: Int, outcome: PinOutcome): Boolean =
        advance(walk.runnable) { applied(walk, line, outcome, it) } != null

    /** Cancellation keeps the token and only writes the state, so both are read. */
    private fun UserDataImport.holds(runToken: UUID): Boolean =
        this.runToken == runToken && state == UserDataImportState.RUNNING

    private fun applied(walk: PinWalk, line: Int, outcome: PinOutcome, current: UserDataImport): UserDataImport {
        // The rows first, the report after: the recorder counts in memory and cannot roll back, so a
        // settlement that throws here must not leave it holding an issue the transaction discards.
        val created = outcome.created?.also { createPin(walk, it) }
        outcome.issues.forEach { walk.recorder.record(it.kind, line, it.subject, it.detail) }
        val delta = if (created == null) 0 else 1
        return current.copy(
            processedPins = current.processedPins + 1,
            createdPins = current.createdPins + delta,
            skippedPins = current.skippedPins + (1 - delta),
            issueCount = current.issueCount + outcome.issues.size,
            issueDetailTruncated = walk.recorder.truncated,
        )
    }

    /**
     * Names are resolved here, inside the settling transaction. One that resolves to nothing is dropped:
     * a name the metadata walk refused must not come back through a membership.
     */
    @Suppress("ImportStateMergedOutsideTransaction")
    // An insert of a row this walk built two frames up, which the rule cannot see from here: it reads
    // one call and the argument is a property. The transaction is `advance`'s, one frame out.
    private fun createPin(walk: PinWalk, created: CreatedPin) {
        pinRepository.savePin(
            created.pin.copy(
                tags = created.tagNames.mapNotNull { tagRepository.findUserTagByName(walk.user, it) },
                boards = created.boardNames.mapNotNull { boardRepository.findBoardForUserByName(walk.user, it) },
            ),
        )
        imageRepository.save(created.image)
    }

    private fun outcomeFor(walk: PinWalk, line: ArchiveLine<ImportedPin>): PinOutcome {
        val pin = line.value
        val fault = pin?.let { pinFault(it) }
        return when {
            pin == null -> reported(UserDataImportIssueKind.LINE_MALFORMED, null, line.failure)
            fault != null -> reported(UserDataImportIssueKind.FIELD_INVALID, pin.sourceContextUrl, fault)
            else -> mediaOutcome(walk, pin)
        }
    }

    private fun pinFault(pin: ImportedPin): String? =
        ImportFieldBounds.descriptionFault(pin.description)
            ?: ImportFieldBounds.blankFault(SOURCE_CONTEXT_URL, pin.sourceContextUrl)
            ?: ImportFieldBounds.referenceCountFault(TAGS_FIELD, pin.tags.size)
            ?: ImportFieldBounds.referenceCountFault(BOARDS_FIELD, pin.boards.size)

    private fun reported(kind: UserDataImportIssueKind, subject: String?, detail: String?): PinOutcome =
        PinOutcome(issues = listOf(PendingIssue(kind, subject, detail)))

    /** Per-pin step 1: everything decidable before a byte is read. */
    private fun mediaOutcome(walk: PinWalk, pin: ImportedPin): PinOutcome {
        val image = pin.image
        val fault = image?.let { ImportFieldBounds.entryPathFault(it.path) }
        return when {
            image == null -> reported(UserDataImportIssueKind.PIN_HAS_NO_MEDIA, pin.sourceContextUrl, null)
            fault != null -> reported(UserDataImportIssueKind.ENTRY_PATH_INVALID, image.path, fault)
            image.path !in walk.entryNames ->
                reported(UserDataImportIssueKind.MEDIA_ENTRY_MISSING, image.path, null)
            else -> boundedMedia(walk, pin, image)
        }
    }

    /**
     * Steps 2 to 5. One arm for the byte bound: [ImageStore.digest] reads it first, so a refusal from
     * the staging pass over the same bytes under the same bound is the same answer.
     */
    private fun boundedMedia(walk: PinWalk, pin: ImportedPin, image: ImportedImage): PinOutcome =
        try {
            digested(walk, pin, image)
        } catch (error: ImageTooLargeException) {
            reported(UserDataImportIssueKind.MEDIA_TOO_LARGE, image.path, error.message)
        }

    /** Step 2 then 3: hashed where it lies, so a medium the account already holds costs no write. */
    private fun digested(walk: PinWalk, pin: ImportedPin, image: ImportedImage): PinOutcome {
        val digest = entryOf(walk, image.path).use { imageStore.digest(it, maxImageBytes) }
        val holders = pinRepository.findPinIdsByContentHashForUser(walk.user, digest)
        return matched(walk, pin, image, holders).with(mismatch(image, digest))
    }

    private fun matched(walk: PinWalk, pin: ImportedPin, image: ImportedImage, holders: List<UUID>): PinOutcome =
        when {
            holders.size > 1 ->
                reported(
                    UserDataImportIssueKind.MEDIA_AMBIGUOUS,
                    image.path,
                    "${holders.size} pins already hold this medium",
                )
            holders.isNotEmpty() -> PinOutcome()
            else -> stagedOutcome(walk, pin, image)
        }

    /** Reported, never acted on: the bytes are the authority, so the pin is created all the same. */
    private fun mismatch(image: ImportedImage, digest: String): PendingIssue? =
        when (image.sha256) {
            digest -> null
            else ->
                PendingIssue(
                    UserDataImportIssueKind.MEDIA_DIGEST_MISMATCH,
                    image.path,
                    "declared ${image.sha256}, read $digest",
                )
        }

    /**
     * The entry the name set announced. A source that then refuses to open it contradicts itself, which
     * is the per-line catch-all's business rather than a report of a medium the archive never carried.
     */
    private fun entryOf(walk: PinWalk, path: String): InputStream =
        walk.source.openEntry(path) ?: error("the archive refused the entry $path")

    /** Step 4: the entry is reopened, since the digest pass consumed the first stream. */
    private fun stagedOutcome(walk: PinWalk, pin: ImportedPin, image: ImportedImage): PinOutcome {
        val staged = entryOf(walk, image.path).use { imageStore.stage(it, maxImageBytes) }
        return try {
            created(walk, pin, staged, imageProbe.probe(staged, maxPixels))
        } catch (error: ImageTooManyPixelsException) {
            imageStore.discardQuietly(staged)
            reported(UserDataImportIssueKind.MEDIA_TOO_MANY_PIXELS, image.path, error.message)
        } catch (error: ImageProbeException) {
            imageStore.discardQuietly(staged)
            reported(UserDataImportIssueKind.MEDIA_UNREADABLE, image.path, error.message)
        }
    }

    /** The probe is the authority on the stored media type and dimensions, never the archive. */
    private fun created(walk: PinWalk, pin: ImportedPin, staged: StagedFile, probe: ProbeResult): PinOutcome {
        val pinId = randomUUID()
        val imageId = randomUUID()
        val createdAt = walk.clamp.clamp(pin.createdAt)
        return PinOutcome(
            created =
                CreatedPin(
                    pin =
                        Pin(
                            id = pinId,
                            author = walk.user,
                            sourceContextUrl = pin.sourceContextUrl,
                            sourceMediaUrl = pin.sourceMediaUrl,
                            description = pin.description,
                            tags = emptyList(),
                            boards = emptyList(),
                            createdAt = createdAt,
                            updatedAt = walk.clamp.clampUpdate(pin.updatedAt, createdAt),
                            softDeletedAt = pin.deletedAt?.let { walk.clamp.clamp(it) },
                        ),
                    image =
                        Image(
                            id = imageId,
                            pinId = pinId,
                            mimeType = probe.format.mimeType,
                            width = probe.width,
                            height = probe.height,
                            animated = probe.animated,
                            byteSize = staged.byteSize,
                            contentHash = staged.contentHash,
                            storageKey = "originals/${walk.user.id}/$pinId/$imageId.${probe.format.extension}",
                            createdAt = walk.importInstant,
                        ),
                    staged = staged,
                    tagNames = pin.tags.map { it.name },
                    boardNames = pin.boards.map { it.name },
                )
        )
    }

    private fun PinOutcome.with(issue: PendingIssue?): PinOutcome =
        when (issue) {
            null -> this
            else -> PinOutcome(listOf(issue) + issues, created)
        }

    /** What one metadata walk accumulates before its single row write. */
    private class MetadataTally(val recorder: ImportIssueRecorder) {
        var created = 0
        var skipped = 0
        var issues = 0

        /** Only the recycled ones: an active board this walk created is a skip nothing reports. */
        val recycled = mutableSetOf<UUID>()
    }

    /** What every pin line needs, gathered once so no per-pin helper carries eight parameters. */
    private class PinWalk(
        val source: ArchiveSource,
        val runnable: RunnableImport,
        val user: User,
        val clamp: ImportInstantClamp,
        val importInstant: Instant,
        val entryNames: Set<String>,
        val recorder: ImportIssueRecorder,
        val renewLease: () -> Unit,
    )

    /** What one `pins.jsonl` line settles into: one transaction writes all of it, or none of it. */
    private class PinOutcome(
        val issues: List<PendingIssue> = emptyList(),
        val created: CreatedPin? = null,
    )

    private class PendingIssue(
        val kind: UserDataImportIssueKind,
        val subject: String?,
        val detail: String?,
    )

    /** A pin whose bytes are staged and whose rows are not written yet; both halves compensate together. */
    private class CreatedPin(
        val pin: Pin,
        val image: Image,
        val staged: StagedFile,
        val tagNames: List<String>,
        val boardNames: List<String>,
    )

    private companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val TAGS_ENTRY = "tags.jsonl"
        const val BOARDS_ENTRY = "boards.jsonl"
        const val PINS_ENTRY = "pins.jsonl"
        const val SOURCE_CONTEXT_URL = "sourceContextUrl"
        const val TAGS_FIELD = "tags"
        const val BOARDS_FIELD = "boards"
        const val USER_GONE = "USER_GONE"
        const val IMPORT_FAILED = "IMPORT_FAILED"
        const val ARCHIVE_UNREADABLE = "ARCHIVE_UNREADABLE"
        const val MANIFEST_MISSING = "MANIFEST_MISSING"
        const val UNSUPPORTED_FORMAT_VERSION = "UNSUPPORTED_FORMAT_VERSION"
    }
}
