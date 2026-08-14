# User data import (portability)

Date: 2026-08-14
Status: approved 2026-08-14, then corrected on seven points the plan angles falsified (marked below)
Depends on: the export archive format (`docs/specs/2026-07-22-user-data-export.md` section 4,
`formatVersion` 1), the task queue (`EnqueueTask`, `CancelTask`, `TaskHandler`, `renewLease`),
`ImageStore`, `ImageProbe`, the pin / board / tag / image repositories, `TransactionRunner`, `Clock`.
One new adapter-only dependency (`jackson-module-kotlin`, version-managed by the Quarkus BOM).

## 1. Goal

Let an authenticated user re-create their pins, boards, tags and images on this instance from an
export archive. Export shipped the first half of the "User data import / export (portability)"
backlog item; this is the second.

Three user scenarios are served by **one** code path:

- **Moving house.** A user opens an account on another instance, or reinstalls their own, and pours
  their archive into an empty account.
- **Merging accounts.** A user pours an archive into an account that already holds data.
- **Restoring.** A user replays an archive after losing data.

Restoring is served **partially**, and section 14 says exactly how much: an import creates what is
absent, so it recovers hard-deleted rows and never recovers an edit, a removed membership or a pin
sitting in the recycle bin.

## 2. Scope

**In scope:**

- **Create an import** (`POST /api/v1/me/imports`), returning `202` and a resource awaiting its archive.
- **Chunked archive upload** (`PUT /api/v1/me/imports/{id}/archive?offset=N`, then
  `POST .../archive/complete`), each chunk bounded so the server-wide body limit does not move.
  Interrupted uploads resume from the byte count the resource reports.
- **Track** (`GET /api/v1/me/imports`, `GET /api/v1/me/imports/{id}`) and **read the report**
  (`GET /api/v1/me/imports/{id}/issues`).
- **Cancel** (`DELETE /api/v1/me/imports/{id}`), with the partial state it leaves.
- **Asynchronous replay** in the worker: create what is missing, skip what is already there, record
  every anomaly, resume from a cursor after any interruption.
- **Validation of archive content**, treated exactly as a request DTO is treated (section 4.1).
- **Two uniqueness constraints**: `(author_id, name collate nocase)` on tags and on boards, covering
  recycled rows. The board one changes a public contract at three sites (section 12).
- **Account deletion erases imports** (rows, issues and archive bytes): section 10.
- **A non-unique index on `images.content_hash`**, without which the pin lookup is a table scan per
  pin (section 11).

**Out of scope (backlog):**

- **Override mode.** Rejected in section 3, with the reason.
- **Selective import** (one board, skip the recycle bin), **partial export**, **merging metadata onto
  an existing pin**, **per-account storage quotas**, **importing pins that carry no medium**,
  **forbidding duplicate media in an account**.

## 3. Key decisions

- **The import never modifies or deletes anything that already exists.** It creates what is missing
  and leaves everything else alone. Every rule below is subordinate to this one, including the
  handling of recycled boards in section 8, which is where the first draft of this spec contradicted
  it.
- **Override mode is rejected, and this is the load-bearing decision.** An earlier design had the
  server compute a plan of overwrites, present it, and apply only the pre-approved set. It was
  dropped because no user scenario needs it: moving house lands in an empty account, merging wants
  addition, and restoring wants what was lost back rather than what survived replaced. Everything
  downstream follows: no two-phase approval, no transactional rollback, no step-up (section 9), and
  idempotence (below).
- **A conflict means skip, and every skip is counted.** Pin skips are counted and, when they are not
  the ordinary "already present" case, detailed. Tag and board skips are counted too, so a merge can
  distinguish "nothing to do" from "did nothing".
- **Identity is a natural key, never an archive UUID.** Archive identifiers are read and discarded;
  every created row gets a fresh `randomUUID()`. Reusing them would let a user choose primary keys,
  and would stop two users of one instance importing the same file.
- **The natural keys: tag by name, board by name, pin by the SHA-256 of its medium.** Name
  comparisons fold ASCII case only (section 12 states the fold and its limit).
- **A pin with no medium in the archive is skipped and reported.** A pin is metadata over a medium;
  with no medium there is nothing to anchor it to and no identity to make a re-import idempotent.
- **When the SHA-256 matches more than one existing pin, the import does nothing and reports it.**
  Nothing binds a medium to at most one pin today, so the key can return several rows. Inventing a
  winner would be arbitrary; a visible refusal is not.
- **The archive's timestamps are restored, clamped at both ends.** Any restored instant earlier than
  the account's `createdAt` is raised to it, any instant later than the import instant is lowered to
  it, and `updatedAt` is floored at the clamped `createdAt`. Clamping only the future, as the first
  draft did, let a syntactically valid `Instant` of year -999999999 reach a pagination sort key.
- **The archive is a request payload and is validated as one.** Section 4.1 restates the bounds the
  REST DTOs carry, because the import is a second write path into the same tables and no entity and
  no use case enforces them today. Without this, an archive plants a 20 MB board name that then
  becomes an index key its owner can never use again.
- **The manifest is never trusted for anything with a consequence.** Every image is probed; the
  stored `mimeType`, dimensions and `animated` flag come from `ImageProbe`, never from the archive.
  `ImageController.serveOriginal` serves the stored media type, so copying an unverified one is
  stored cross-site scripting by the shortest path. Two harmless uses remain: knowing which entries
  to expect, and rendering progress.
- **The declared digest is compared and a mismatch is reported, never acted on.** It costs one
  string comparison against a digest the import computes anyway, and it is the only signal that an
  archive was altered or truncated in transit. It changes no outcome: the bytes are the authority.
- **The digest is computed without staging, and the file is staged only after the lookup misses.**
  `ImageStore.stage` writes the whole entry to a temp file and calls `force(true)` before returning,
  so digesting through it would cost a full write plus an fsync plus a delete for every image the
  import is about to skip, which is the dominant path in merging and restoring. `ImageStore` gains a
  read-only `digest` operation; the archive entry is reopened for the staging pass on a miss.
- **The runner is fenced.** Each per-pin transaction re-reads the import row and proceeds only if it
  still holds the run. Without this, a lease expiry hands the same import to a second worker, both
  see no pin for a digest, both create one, and that medium is `MEDIA_AMBIGUOUS` for ever: a
  transient delay permanently poisons the natural key. The same mechanism carries cancellation.
- **A single bad entry never fails the import.** It is skipped, reported, and the walk continues.
  All-or-nothing would waste thousands of good pins over one truncated file and leave a half-filled
  account under a status that reads "failed".
- **The report details anomalies and counts the rest.** Detail is capped (section 9); the response
  says when it was capped rather than lying by omission.
- **Upload is chunked, and the server-wide body limit does not move.** `max-body-size` is declared
  once in `ServerLimitsConfig` for the whole server, with no per-route override, and it is currently
  the only bound on two `@PermitAll` routes (`POST /api/v1/users`, `POST /api/v1/sessions`) whose
  bodies RESTEasy materialises before validation or attempt limiting runs. It is also the only bound
  on the multipart image route, which spools the whole body to disk before `SetPinImage` sees a byte.
  Raising it to multi-gigabyte would therefore hand an unauthenticated caller both heap exhaustion
  and disk exhaustion. Chunking keeps every request under the existing limit and, as a side effect,
  makes an interrupted upload resumable rather than a full re-transfer.

## 4. What the importer reads

Input contract: the archive format of `docs/specs/2026-07-22-user-data-export.md` section 4,
`formatVersion` 1. Any other value is refused (section 10). The accepted version is a single constant
shared with the writer rather than a second copy of the literal.

| Entry | Read for | Ignored |
|---|---|---|
| `manifest.json` | `formatVersion` (a decision), `counts.pins` (progress display only) | `entries`, `generator`, `excluded` |
| `tags.jsonl` | `name`, `createdAt` | `id` |
| `boards.jsonl` | `name`, `description`, `createdAt`, `updatedAt`, `deletedAt` | `id` |
| `pins.jsonl` | `description`, `sourceContextUrl`, `sourceMediaUrl`, timestamps, `deletedAt`, `tags[].name`, `boards[].name`, `image.path`, `image.sha256` (reported only) | `image.mimeType`, `width`, `height`, `animated`, `byteSize`, all `id` fields |
| `images/*` | the bytes | the entry name, except to locate an entry |
| `user.json`, `README.md` | nothing | everything |

`user.json` is not imported: the target account already exists with its own name and creation date.

**Entry paths are validated, never trusted.** A pin's `image.path` must match the anchored pattern
`^images/[A-Za-z0-9._-]+$` and must not end in a segment of `.` or `..`. Structurally, traversal
cannot reach the disk anyway: an entry name is only ever a ZIP lookup key, and every write goes
through `ImageStore` under a key the import builds from fresh identifiers. The check exists so a
malformed archive is reported rather than silently skipped, which is why it must actually report the
paths a reader expects it to.

### 4.1 Field validation

The import writes to `pins`, `boards` and `tags` through the repositories. Every bound those tables
enjoy today lives on a REST input DTO (`BoardInputDto`, `PinCreationInputDto`, `PinTagsInputDto`);
no entity and no use case carries an invariant. The import therefore restates them, per line, before
writing:

| Field | Bound | Source of the bound |
|---|---|---|
| board name | non-blank, at most 200 characters | `BoardInputDto` |
| tag name | non-blank, at most 200 characters | non-blank from `PinTagsInputDto`; the length bound is **new**, since nothing bounds a tag name today |
| board / pin description | at most 2000 characters | `BoardInputDto`, `PinCreationInputDto` |
| `sourceContextUrl` | non-blank | `PinCreationInputDto` |
| `tags[]`, `boards[]` per pin | at most 100 entries each | new: nothing bounds them today, and the list is resolved inside one transaction |

A line failing any of these yields `FIELD_INVALID` and is skipped. The bounds are stated here rather
than lifted into the entities because lifting them is a change to a shipped write path and belongs to
its own lot; the backlog carries it.

## 5. Domain and ports

Packages: entities in `domain.entities`, enums in `domain.enums`, the store port and its exception in
`domain.imports`, mirroring how the export splits `domain.exports` from `domain.enums`.

```kotlin
data class UserDataImport(
    override val id: UUID,
    val userId: UUID,
    val state: UserDataImportState,
    val requestedAt: Instant,
    val taskId: UUID? = null,
    val runToken: UUID? = null,
    val uploadedBytes: Long = 0,
    val lastUploadActivityAt: Instant? = null,
    val archiveCompletedAt: Instant? = null,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val storageKey: String? = null,
    val byteSize: Long? = null,
    val formatVersion: Int? = null,
    val announcedPins: Int? = null,
    val processedPins: Int = 0,
    val createdPins: Int = 0,
    val skippedPins: Int = 0,
    val createdBoards: Int = 0,
    val skippedBoards: Int = 0,
    val createdTags: Int = 0,
    val skippedTags: Int = 0,
    val issueCount: Int = 0,
    val issueDetailTruncated: Boolean = false,
    val failureCode: String? = null,
) : Identifiable
```

`UserDataImportState`: `AWAITING_ARCHIVE`, `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`,
`ABANDONED`. `isActive` covers the first three and is what the one-import-at-a-time index tests.

`runToken` is the fence. A runner claims the row by writing a fresh token, and every subsequent
per-pin transaction proceeds only if the persisted token is still its own (section 8). It is also how
cancellation is observed: the canceller writes `CANCELLED`, and the next per-pin re-read stops the
walk.

`processedPins` is the cursor. Counters are **incremented**, never assigned, so a resumed attempt
adds to what the interrupted one had already counted; `startedAt` is stamped only when null.

**The runner works from a non-nullable projection.** `RunnableImport(importId, userId, storageKey,
runToken)` is built at one validation site in step 1 of section 8; a `PENDING` row missing its
`storageKey` throws there, from a single reachable branch, and the rest of the walk handles no
nullables. This is `OpenedExport`'s answer to the same coverage trap, and it is preferred to the
`!!` plus suppression that `UserDataExportDeleter` uses, which `agents/engineering.md` forbids.

### `UserDataImportIssue`

```kotlin
data class UserDataImportIssue(
    override val id: UUID,
    val importId: UUID,
    val kind: UserDataImportIssueKind,
    val line: Int?,
    val subject: String?,
    val detail: String?,
) : Identifiable
```

`UserDataImportIssueKind`: `PIN_HAS_NO_MEDIA`, `MEDIA_ENTRY_MISSING`, `MEDIA_UNREADABLE`,
`MEDIA_TOO_LARGE`, `MEDIA_TOO_MANY_PIXELS`, `MEDIA_AMBIGUOUS`, `MEDIA_DIGEST_MISMATCH`,
`LINE_MALFORMED`, `FIELD_INVALID`, `ENTRY_PATH_INVALID`, `NAME_TAKEN_BY_RECYCLED`, `LINE_REJECTED`.

`LINE_REJECTED` is the catch-all that makes "a single bad entry never fails the import" structural
rather than aspirational: any per-line failure with no more specific kind lands there with its cause
in `detail`. "Already present" is not an issue kind; it is a counter.

`subject` and `detail` are truncated to 200 characters before storage, so a hostile line cannot make
the report itself the payload.

### `ImportArchiveStore` port

```kotlin
interface ArchiveLine<out T> {
    val line: Int
    val value: T?
    val failure: String?
}

interface ArchiveSource : AutoCloseable {
    fun entryNames(maxEntries: Int): Set<String>
    fun <T : Any> readJson(name: String, type: Class<T>, maxBytes: Long): T?
    fun <T : Any> readJsonLines(name: String, type: Class<T>, block: (Sequence<ArchiveLine<T>>) -> Unit)
    fun openEntry(name: String): InputStream?
}

interface ImportArchiveStore {
    fun hasFreeSpace(requiredBytes: Long): Boolean
    fun appendChunk(importId: UUID, offset: Long, bytes: InputStream, maxTotalBytes: Long): Long
    fun finishUpload(importId: UUID): StagedFile
    fun promote(staged: StagedFile, storageKey: String)
    fun open(storageKey: String): ArchiveSource
    fun delete(storageKey: String)
    fun discardPartialUpload(importId: UUID)
    fun discardOrphanedStagedFiles(olderThan: Instant): Int
    fun forEachStorageKeyOnDisk(block: (Sequence<String>) -> Unit)
}
```

`appendChunk` refuses an `offset` that is not the current length, refuses a chunk that would carry
the total past `maxTotalBytes`, and returns the new length. `finishUpload` fsyncs and digests, giving
the same `StagedFile` the export's staging produces. The lazy sequence keeps `ExportArchiveStore`'s
loan contract: the adapter owns the entry stream and closes it when `block` returns.

**Every read is bounded**, because the archive is hostile input: `entryNames` refuses past
`maxEntries`, `readJson` past `maxBytes`, and `readJsonLines` refuses a line longer than
`imports.max_line_bytes` rather than allocating until the heap is gone. Jackson's
`StreamReadConstraints` are configured explicitly on the reader-only mapper rather than left at their
defaults, which bound a single string but not a document.

**Deserialization needs `jackson-module-kotlin`.** Not, as the first draft claimed, because Jackson
cannot instantiate a data class: the build sets `javaParameters`, so parameter names are in the
bytecode and a module that reads them can bind a primary constructor. (`jackson-module-parameter-names`
is **not** on this module's classpath and is declared nowhere in the build; an earlier revision said
it was.) The reason to take the Kotlin module is **null safety**: without
the Kotlin module, a missing or null JSON field lands as `null` inside a non-nullable Kotlin property
and fails later at an unrelated site, which is precisely what an archive-driven test suite must not
depend on. Verified absent from the module's classpath
(`./gradlew :api-storage-filesystem:dependencies --configuration runtimeClasspath` resolves
`jackson-core`, `-databind`, `-annotations`, `-datatype-jsr310` and nothing else). It is registered
on a reader-only `ObjectMapper`; the export builds its own explicitly, with no
`findAndRegisterModules()`, so the written format cannot move.

**`ExportContentGoldenJsonTest` is not the proof of that, contrary to what an earlier revision
said.** It lives in `api-usecases` and builds a replica mapper, and its own KDoc says it stops
proving anything about the real archive if the adapter's configuration drifts. It also asserts, in
prose, that no `jackson-module-kotlin` exists anywhere in this codebase, which this lot falsifies.
The proof that the writer is unchanged is therefore an assertion in `api-storage-filesystem` over the
writer mapper's registered module ids, plus the cross-adapter round trip; and the golden test's KDoc
is corrected in the commit that adds the dependency.

`ImportAlreadyInProgressException` lives in `domain.imports`, not in `api-usecases`: the persistence
adapter throws it and the use case rethrows `ImportAlreadyInProgressError`. `api-persistence-sqlite`
cannot see a `BaseError`, which is why `ExportAlreadyInProgressException` exists in the domain.

### Repository ports

`UserDataImportRepositoryInterface`: `save`, `findById`, `findAllForUser(userId, cursor, pageSize)`,
`findAbandonableBefore(instant)`, `findReclaimableTerminal()`, `findMissingImportIds(candidates)`,
`findAllImportIdsForUser`, `deleteAllForUser`.

There is deliberately **no** `findActiveForUser` used as a pre-insert check. ADR 0009 decision 2
forbids a read that exists solely to answer a uniqueness question an index already answers, and the
export's retained read is that ADR's one written exception, kept because it orders a `409` ahead of a
`429`. This import has no minimum interval and therefore no second refusal to order, so the index is
the only authority and the adapter translates its violation.

`UserDataImportIssueRepositoryInterface`: `save`, `findAllForImport(importId, cursor, pageSize)`,
`countForImport`, `deleteAllForImport`, `deleteAllForUser`.

`PinRepositoryInterface` gains **`findPinIdsByContentHashForUser(user, contentHash): List<UUID>`**,
and it lives there rather than on `ImageRepositoryInterface` because `ImageModel` carries `pinId` as
a plain column with no association to `PinModel`, so a query rooted on images can reach neither the
author nor the soft-delete state. It reads **every state** (`PinQueries.any()`), per ADR 0008's
requirement that a read name its state: filtering to active pins would silently create a second copy
of a pin the user had recycled, which breaks idempotence. It returns a list, not a nullable row,
because no constraint guarantees at most one.

`BoardRepositoryInterface` gains **`findBoardForUserByName(user, name): Board?`**, case insensitive,
reading **every state**: a recycled board holds its name (section 12), so a finder blind to the
recycle bin would try to create a board whose name is already taken and hit the index.

`TagRepositoryInterface.findUserTagByName` becomes case insensitive, following `UserRepository`'s
`ieq` against `ix_users_name_nocase`.

## 6. Use cases

- **`UserDataImportCreator.create(user)`**: inserts an `AWAITING_ARCHIVE` row, letting the partial
  unique index refuse a second active import. No step-up (section 9), no task yet.
- **`UserDataImportChunkReceiver.receive(user, importId, offset, bytes)`**: owner check, then state
  check (`AWAITING_ARCHIVE` only), then a free-space check, then `appendChunk`, then stamps
  `uploadedBytes` and `lastUploadActivityAt`. Refuses an out-of-order offset with the current length
  so a client can resume without guessing.
- **`UserDataImportArchiveCompleter.complete(user, importId)`**: `finishUpload`, write `storageKey`
  and `byteSize`, promote, move to `PENDING`, enqueue `account.import` at an explicit priority of
  `-1`, store the task id. Every task kind in this system currently runs at the default priority of
  `0`: no call site passes the argument, and the kind is `account.delete`, not `account.deletion`. So
  "below account deletion" is only expressible as a negative number, and this is the first use of the
  field. The storage key is written **before** the promote, as the export
  builder does, so bytes are reclaimable even if the row is never written again.

  **The transition, the enqueue and the task id write are one transaction**, under the same
  `AWAITING_ARCHIVE` fence as the storage key write before them. Split in two, a crash or a database
  error between the `PENDING` write and the enqueue leaves a row `PENDING` with no task, which no sweep
  rescues: `findAbandonableBefore` is `AWAITING_ARCHIVE`-only, `findReclaimableTerminal` is
  terminal-only, and the reaper's third path only rescues a `RUNNING` row whose task is dead. That row
  holds the account's only active slot through the partial unique index and its bytes are never
  reclaimed. Joined, a failure anywhere in the hand-over leaves the row `AWAITING_ARCHIVE`, which the
  upload-grace sweep does cover. It also settles a second question: a `PENDING` or `RUNNING` row always
  carries its `taskId`, since the task and the id that names it commit together.

**The upload writes are fenced on `AWAITING_ARCHIVE`, and a lost fence answers
`IMPORT_NOT_AWAITING_ARCHIVE`.** That is one fence in the receiver and two in the completer, the second
of which guards the whole hand-over; each takes the same predicate, and the completer's three row
writes therefore sit behind two reads of it. This was not specified, and its absence left two unfenced writes
standing while the rest of the feature was fenced. The windows are wide, not theoretical: the receiver
streams a chunk to disk between its read and its write, and the completer fsyncs and digests up to
twenty gigabytes before promoting. A `DELETE` landing in either window writes `CANCELLED`, and an
unfenced save then restores `AWAITING_ARCHIVE` or writes `PENDING`, leaving an import the user was
told was cancelled holding the account's only active slot, in the completer's case with a task
enqueued for it.

The refusal needs no new code: the caller asked to feed or close an import that is no longer taking an
archive, which is exactly what `IMPORT_NOT_AWAITING_ARCHIVE` already says, and the client's next `GET`
shows `CANCELLED`. Bytes promoted before a lost fence are deleted best-effort on the way out, and the
sweep is the guarantor if that delete fails.
- **`UserDataImportRunner.run(importId, isLastAttempt, renewLease)`**: the worker path (section 8).
- **`UserDataImportGetter`**, **`UserDataImportIssueLister`**: owner-checked reads.
- **`UserDataImportCanceller.cancel(user, importId)`**: owner check; `AWAITING_ARCHIVE` discards the
  partial upload and moves to `CANCELLED` (no task exists yet, so none is cancelled); `PENDING`
  cancels the task, deletes the archive and moves to `CANCELLED`; `RUNNING` writes `CANCELLED` and
  lets the fence stop the walk at the next pin, the runner deleting the archive as it returns;
  terminal states are a no-op. Rows already written stay: an import is not a transaction.
- **`ReapAbandonedUserDataImports.reap()`**: moves `AWAITING_ARCHIVE` rows whose
  `lastUploadActivityAt` (falling back to `requestedAt`) is older than `imports.upload_grace` to
  `ABANDONED`, discarding their partial uploads; deletes the archive bytes of terminal rows that
  still have some, stamping the row so the same bytes are not re-deleted every hour; moves a
  `RUNNING` row whose task is `DEAD` or absent to `FAILED` with `IMPORT_INTERRUPTED`; and sweeps
  orphaned staged files.
- **`ReapOrphanedStorage`** gains the import half, pairing `forEachStorageKeyOnDisk` with
  `findMissingImportIds`, exactly as it already does for exports. Without it, an archive promoted by
  a completer that died before writing its row is unreclaimable, and ADR 0003 makes the sweep the
  guarantor of residue.
- **`AccountDeletionCleaner`** deletes issues, then import rows, inside its transaction (before the
  user row), and the archive bytes after the commit, keyed on the **derived** key rather than the
  stored column, so an archive promoted by a completer that died is still reclaimed. This mirrors the
  export half already in that class.
- **`UserDataImportTask`**: `KIND = "account.import"`, `MAX_ATTEMPTS = 5`.

## 7. REST surface

All endpoints `@Authenticated` and owner-scoped (`403` for a non-owner, `404` for an unknown id),
owner checked before state.

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/v1/me/imports` | `202`, state `AWAITING_ARCHIVE` |
| `PUT` | `/api/v1/me/imports/{id}/archive?offset=N` | `application/octet-stream`, one chunk, `200` with the new length |
| `POST` | `/api/v1/me/imports/{id}/archive/complete` | `202`, state `PENDING` |
| `GET` | `/api/v1/me/imports` | Paginated history (cursor) |
| `GET` | `/api/v1/me/imports/{id}` | State and counters |
| `GET` | `/api/v1/me/imports/{id}/issues` | Paginated report |
| `DELETE` | `/api/v1/me/imports/{id}` | `204` |

Response DTO: `id`, `state`, `requestedAt`, `uploadedBytes`, `byteSize`, `archiveCompletedAt`,
`startedAt`, `completedAt`, `formatVersion`, `announcedPins`, `processedPins`, `createdPins`,
`skippedPins`, `createdBoards`, `skippedBoards`, `createdTags`, `skippedTags`, `issueCount`,
`issueDetailTruncated`, `failureCode`.

**No progress field.** The two counters ship raw and the client renders what it wants; a server-side
ratio would add two degenerate branches (`announcedPins` null or zero) to publish nothing the client
cannot compute.

A chunk body is consumed as an `InputStream` and streamed to `appendChunk`. Streaming holds while the
endpoint stays blocking and while no extension installs a global Vert.x body handler; both would
buffer silently, so the endpoint is annotated blocking deliberately and the condition is written here.

## 8. The `account.import` task

`UserDataImportTaskHandler` delegates to the runner with
`isLastAttempt = context.attempt >= context.maxAttempts`, the comparison the export handler uses.

1. Load the import; return unless `PENDING` or `RUNNING`. Load the user; if gone, `FAILED` with
   `USER_GONE` and `PermanentTaskException`. Build the `RunnableImport` projection.
2. Claim the run: in one transaction, write a fresh `runToken`, move to `RUNNING`, stamp `startedAt`
   if null.
3. Open the archive. Unreadable, manifest absent, or `formatVersion != 1`: `FAILED` with the matching
   code and `PermanentTaskException`. Retrying cannot help. Record `announcedPins`.
4. Walk `tags.jsonl`: validate the line, then find-or-create by name, restoring `createdAt` on
   creation. `renewLease()` every `imports.lease_renewal_lines` lines.
5. Walk `boards.jsonl` the same way. **A board that already exists is left untouched and counted in
   `skippedBoards`, whatever its state and whatever the archive says.** Only a board the import
   creates carries the archive's description, timestamps and, when the archive has a `deletedAt`,
   its recycled state. A name held by a recycled board the import did not create yields
   `NAME_TAKEN_BY_RECYCLED`.
6. Walk `pins.jsonl` from `processedPins`, one pin at a time (below), renewing the lease per pin and
   during the forward skip to the cursor.
7. In one transaction, re-read the row and write `COMPLETED` **only if it still holds the run**
   (`runToken` unchanged and state `RUNNING`). Otherwise return without writing. A blind write would
   resurrect an import the user was told was cancelled, or re-insert a row for a deleted account,
   which foreign keys would not stop since enforcement is off on this datasource.
8. Delete the archive bytes.

Per pin, in order:

1. Validate the line (section 4.1). No `image`: `PIN_HAS_NO_MEDIA`. Path malformed:
   `ENTRY_PATH_INVALID`. Entry absent: `MEDIA_ENTRY_MISSING`.
2. `imageStore.digest(entry, images.max_file_bytes)`: reads and hashes without writing. Over the
   bound: `MEDIA_TOO_LARGE`. Compare with the archive's declared `image.sha256`; a mismatch is
   recorded as `MEDIA_DIGEST_MISMATCH` and changes nothing else.
3. `findPinIdsByContentHashForUser`: one hit is already present and counts in `skippedPins`; more
   than one is `MEDIA_AMBIGUOUS`. Either way, nothing is staged and nothing is written.
4. Zero hits: reopen the entry, `imageStore.stage(entry, images.max_file_bytes)`, then
   `imageProbe.probe(staged, images.max_pixels)`. Probe failure is `MEDIA_UNREADABLE`, a pixel-count
   refusal is `MEDIA_TOO_MANY_PIXELS`; both discard the staged file.
5. Promote the bytes, then in **one transaction**: re-read the row, proceed only if it still holds
   the run, create the pin with the clamped timestamps, create the image row, resolve tags and boards
   by name, write memberships through `Pin.boards` as any other writer does, apply `deletedAt`, and
   increment the cursor and counters. If the fence fails or the transaction throws, delete the
   promoted bytes and discard the staged file, as `SetPinImage`'s compensation does.

   **Correction.** An earlier revision of this section forbade passing through `Pin.boards`, on the
   claim that saving a mapped pin would delete the join rows of recycled boards. That is false:
   `PinRepository.savePinBoards` diffs only against active memberships, deliberately and with a
   comment saying so, precisely so a recycled board's join row survives a re-save. The ordinary path
   is the correct one and no second membership writer is needed.

Every issue row is written in the same transaction as the cursor increment that settles its line, so
a crash cannot duplicate or lose it.

**Any unexpected throw marks the row `FAILED` with `IMPORT_FAILED` when `isLastAttempt`**, then
rethrows. Without it, an unenumerated failure leaves the row `RUNNING` for ever, the archive
unreclaimed, and the account locked out of importing by the partial unique index.

`TaskProcessor` logs handler failures at WARN and logs dead-lettering, but nothing asserts logs and
no alerting is wired, so the import row's state, `failureCode` and issues remain the surface a user
and an operator read. The first draft of this spec inherited a claim from the export spec that
`TaskProcessor` swallows exceptions silently; that was true when the export spec was written and
stopped being true on 2026-08-01. The dated document keeps its sentence; this one does not repeat it.

## 9. Quotas, configuration and lifecycle

- **No step-up re-authentication.** Operator decision, taken with the counter-argument on the table.
  The counter-argument, recorded because it is not weak: what this codebase gates is unbounded
  effect, not exfiltration, and `AccountDeleter` is the precedent; undoing an unwanted import costs
  one request per pin with no bulk operation available; and section 12 lets an archive take a board
  name its owner then cannot use. The accepted worst case is written in section 14 rather than left
  implicit, and the reopening condition in ADR 0015 is stated as a property, not as one feature's
  return.
- **One active import per user**, enforced by a partial unique index over the three active states and
  translated by the adapter. Verified on `sqlite3` that an `IN`-list predicate accepts a second
  terminal row and refuses a second active one.
- **No minimum interval between two imports**: someone repatriating three old accounts chains them.
- **Free space is checked before every chunk**, against a fixed margin. The first draft dropped the
  check on the grounds that the extraction size cannot be estimated without believing the manifest.
  That reasoning covered the walk and ignored the upload, which is where the danger is: the default
  deployment points every data directory at the volume that also holds the SQLite database, so an
  unbounded upload takes the whole instance down, not just the import.
- **Disk-full during the walk is transient and retried**, with the cursor resuming. For that to mean
  anything the retry budget must outlast an operator, so `account.import` uses
  `MAX_ATTEMPTS = 5` with a backoff floor of `imports.retry_floor`; at the queue's default backoff
  the three attempts of the first draft were consumed in about three seconds, which no operator can
  use. The archive survives until the row is terminal, so a retry does not re-upload.

  **It is not, however, named as its own failure code.** An earlier revision listed `DISK_FULL` among
  the row's failure codes, copying the export, which can use it because it asks `hasFreeSpace` before
  building and therefore knows. The walk does not ask: a full disk reaches it as an `IOException`
  whose message is platform-dependent, so telling it apart from a permission error or a truncated
  read means matching on text. The code is dropped rather than shipped unreachable or shipped lying.
  A disk-full walk retries like any other transient failure and, if space never returns, ends
  `IMPORT_FAILED`. The real guard is upstream, where `IMPORT_INSUFFICIENT_STORAGE` refuses a chunk
  against a measured margin.

| Key | Default | Meaning |
|---|---|---|
| `imports.data_dir` | `/var/lib/pinry/imports` | Uploaded archives (a new volume) |
| `imports.max_archive_bytes` | `21474836480` (20 GiB) | Refused past this, per import |
| `imports.max_chunk_bytes` | `16777216` (16 MiB) | Strictly under `quarkus.http.limits.max-body-size`, which is `32M`, meaning 33554432 bytes. The 32 MiB this table first carried was exactly equal to it, so the invariant was violated by its own default |
| `imports.max_entries` | `200000` | Archive entry-count bound |
| `imports.max_metadata_bytes` | `16777216` (16 MiB) | Per non-image entry read whole |
| `imports.max_line_bytes` | `1048576` (1 MiB) | Per JSONL line |
| `imports.minimum_free_bytes` | `1073741824` (1 GiB) | Margin required before accepting a chunk |
| `imports.upload_grace` | `PT24H` | Inactivity past which an upload is abandoned |
| `imports.sweep_interval` | `PT1H` | How often the sweep runs |
| `imports.staged_file_max_age` | `PT48H` | Age past which a partial upload is orphaned |
| `imports.lease_renewal_lines` | `200` | Lines between two lease renewals in the metadata walks |
| `imports.retry_floor` | `PT10M` | Backoff floor for this task kind |
| `imports.report_detail_limit` | `500` | Issues stored per import; past it only `issueCount` grows |

`imports.upload_grace` counts **inactivity**, not age: a grace measured from creation would abandon a
multi-hour upload while it was still streaming. Every default lives in the `@ConfigMapping`
(`ImportsConfig` in `api-worker-quarkus`, next to `ExportsConfig`), and `application.properties` gains
`imports.*` to its prefix inventory. `images.max_file_bytes` and `images.max_pixels` are **reused**,
passed to the runner as constructor parameters wired at the composition root, since `ImagesConfig`
lives in the presentation module and `api-usecases` cannot see it.

The sweep runs on its own `PeriodicScheduler`, injected by type (ADR 0004).

**Deployment**: `imports.data_dir` is a third dataset. The `Dockerfile` comment gains
`IMPORTS_DATA_DIR` alongside the two it names, and the directory is checked writable at startup
rather than on first write, so an unwritable volume refuses to boot instead of failing after a user
has streamed twenty gigabytes. A reverse proxy needs no change, since every request stays under the
existing body limit; that is a direct benefit of chunking.

## 10. Errors

| Code | Status | When |
|---|---|---|
| `IMPORT_ALREADY_IN_PROGRESS` | `409` | An active import already exists |
| `IMPORT_DOES_NOT_EXIST` | `404` | Unknown id |
| `IMPORT_INSUFFICIENT_PERMISSIONS` | `403` | Not the owner |
| `IMPORT_NOT_AWAITING_ARCHIVE` | `409` | A chunk or completion for an import past the upload phase |
| `IMPORT_CHUNK_OFFSET_MISMATCH` | `409` | Offset is not the current length; the body names the current length |
| `IMPORT_ARCHIVE_EMPTY` | `409` | Completion for an import that received no chunk |
| `IMPORT_ARCHIVE_TOO_LARGE` | `413` | The chunk would carry the total past `imports.max_archive_bytes` |
| `IMPORT_INSUFFICIENT_STORAGE` | `507` | Free space below the margin |
| `BOARD_NAME_ALREADY_EXISTS` | `409` | Section 12, at all three sites |

Naming follows `USERNAME_ALREADY_EXISTS` and its `UsernameAlreadyTakenException`, rather than
inventing a second vocabulary for the same concept.

**Correction (block 2 review).** `IMPORT_ARCHIVE_EMPTY` is added after the fact. Section 6's
completer went straight to `finishUpload`, which opens the upload file, so an import that received no
chunk raised `NoSuchFileException` and reached its owner as a `500`. The row's `uploadedBytes`
settles it in the use case, before the store is touched.

Failure codes on the row: `USER_GONE`, `ARCHIVE_UNREADABLE`, `MANIFEST_MISSING`,
`UNSUPPORTED_FORMAT_VERSION`, `IMPORT_FAILED`, `IMPORT_INTERRUPTED`. `DISK_FULL` was listed here in
an earlier revision and is dropped: see section 9 for why the walk cannot honestly tell a full disk
from any other I/O failure.

Each new code is an arm of `BaseErrorMapper`'s exhaustive `when`, landing in the same commit as the
enum value. `jakarta.ws.rs` has **no** `INSUFFICIENT_STORAGE` constant (verified with `javap` on
`jakarta.ws.rs-api-3.1.0.jar`: `Response$Status` stops at `NETWORK_AUTHENTICATION_REQUIRED`).
Nothing new is needed: `BaseErrorMapper.statusFor` already returns a raw `Int` and already carries
this workaround for `IMAGE_INVALID`. The title fallback is currently hardcoded to the 422 wording, so
it is generalised in the same commit rather than shipping a 507 titled "Unprocessable Entity".

## 11. Persistence and migration

The schema change spans **three generated migrations**, not one, because three tasks touch the schema
and each generates its own pair against the model directory as it stands; the generator owns the
numbers and no document asserts them. Together they create `user_data_imports` and
`user_data_import_issues`, add the two unique
indexes of section 12, adds a **non-unique** index on `images (content_hash)`, and adds the partial
unique index over the active import states.

**Every index is declared on its entity** with `@Index(name = ..., definition = "create unique index
...")` and the migration is produced by `./gradlew :api-persistence-sqlite:generateDbMigration`, so
each `.sql` has its paired `model/<version>.model.xml`. The first draft said the partial index would
be hand-written "following the export precedent"; that precedent was removed on 2026-07-23, ADR 0009
decision 5 emptied the hand-written allowlist, and `DbMigrationModelCoverageTest` holds
`handWritten = emptySet()`. A hand-written file would fail the gate on the first commit, and the
stated reason was inverted: it is hand-writing that lets a later regeneration drop an index.

Indexes for the reads this feature performs: `(user_id, state)` on imports, `(import_id)` on issues.
Note that the partial unique index constrains but does not serve a query whose predicate binds a
parameter, per `agents/engineering.md`.

`content_hash` gets its index because `findPinIdsByContentHashForUser` runs once per pin, and today
`ImageModel` carries no index on that column: without it the import is pins times images.

States are stored as plain `String` columns converted in the mapper, following `TaskModel` and
`UserDataExportModel`. Column names follow the tables they join: boards and tags key on `author_id`.

## 12. The uniqueness constraints, and the contract they change

The import needs a name to be an identity. Today it is not one.

- **Tags.** `(author_id, name collate nocase)` closes the race, but **it does change something
  observable**, contrary to what an earlier revision claimed. The index alone changes nothing; making
  the read fold with it does: `PUT /api/v1/pins/{id}/tags` with `Landscape` when `landscape` is
  stored now returns the stored spelling and creates no second tag. That change is not optional. With
  the case-sensitive read left in place, the same request produces an untranslated constraint
  violation and the client gets a `500` (measured by reverting the read and running
  `PinTaggingIntegrationTest`). Its outcome is named: no
  translation, deliberately, because the read-then-write inside one transaction makes the violation
  unreachable from the API; a concurrent pair is serialised by the single connection.
- **Boards.** `BoardCreator.create` checks nothing, so `POST /api/v1/boards` with a taken name
  currently succeeds. Under the constraint it returns `409 BOARD_NAME_ALREADY_EXISTS`. This is a
  deliberate breaking change to a public contract: alpha status allows it, no instance is deployed,
  and a name cannot be an identity while the system lets it be ambiguous.

**The constraint fires at two write sites, not three.** Creation and renaming both reach
`BoardRepository.saveBoard`, which translates the violation; each use case rethrows the domain error,
so no persistence exception reaches a controller.

**Restoration is not a third site**, and two earlier revisions of this section said it was. Because
the index covers recycled rows, no homonym can exist while a board sits in the recycle bin, and
`restoreBoard` changes no indexed column: the collision is unreachable, a `catch` there would be dead
code, and the test that was prescribed for it could not be set up, since creating the collision is
itself refused by the constraint under test. The paragraph below already said as much
("restoring from the recycle bin can no longer collide") without the contradiction being noticed. The
reachable half of the rule is what ships: a recycled board holds its name, the `409` says so, and
emptying the bin releases it.

**The `409` detail is read after the refusal, not before it.** Naming the recycled holder needs a
lookup, which ADR 0009 decision 2 permits: it bars a read that *decides* uniqueness, not one that
explains a refusal the database has already made. The holder is nullable, since a concurrent hard
delete can free the name in between, and that third branch is a case of its own.

**Recycled rows hold their name.** Operator decision. The index covers every row, like
`ix_users_name_nocase`. Consequences, all deliberate: a board in the recycle bin blocks a new board
of that name until the bin is emptied, so the `409` detail says the name is held by a recycled board
rather than leaving the client guessing; restoring from the recycle bin can no longer collide, since
no homonym can have been created meanwhile; and the import's board finder reads every state.

**The fold is ASCII, and the read must use the index's fold rather than Java's.** `collate nocase`
folds A to Z and nothing else, so `ÉTÉ` and `été` remain two names: a stated limit rather than a
claim of case insensitivity in general. The trap is that Ebean's `ieq` renders `lower(column) = ?`
with the bound value lowercased in Java, which **is** Unicode aware, so read and index disagree in
one direction: with `ÉTÉ` stored, a lookup for `été` misses, while the reverse matches. Both name
lookups therefore compare through the column's own collation
(`raw("name collate nocase = ?", name)`), so the read folds exactly as the index does. Tested in both
directions, because the one-directional test the precedent carries would not have caught this.

## 13. Testing strategy

TDD, 100% branch coverage per package. Each scenario names where it lives, because a new
`@QuarkusTest` costs a full boot and `agents/engineering.md` requires that to be justified.

1. **Round trip** (integration, joins the export suite). Seed pins including one recycled, a recycled
   board holding a pin, tags, real images, and **two pins sharing one image**. Export, download the
   bytes, create a second account, upload and import. Equivalence is enumerated, not asserted as a
   word: same pin count by natural key; per pin the same `description`, `sourceContextUrl`,
   `sourceMediaUrl`, `createdAt`, `updatedAt`, `deletedAt`, tag-name set and board-name set including
   the recycled board; same board set with their `deletedAt`; same tag set; image bytes byte-identical;
   and every identifier different from the original. The duplicate-media pair must produce one pin and
   one `MEDIA_AMBIGUOUS`-free skip, pinning the within-archive collision that section 14 records.
2. **Timestamps** (use-case unit), three assertions in one archive: a past `createdAt` restored
   exactly, a future one equal to the import instant, and the `UserDataImport` row's own timestamps
   equal to the injected `Clock`. The first assertion is the one that fails an implementation that
   re-dates everything, which the clamp-only test of the first draft did not.
3. **Idempotence** (use-case unit). Import twice against a counting fake `ImageProbe`: the account
   projection is identical after both runs, the second run's counters are `createdPins = 0`,
   `skippedPins = N`, and the probe call count is unchanged, which pins the digest-before-stage
   ordering that section 3 claims.
4. **Resumption** (use-case unit). A fake `ArchiveSource` whose sequence throws on line three; a
   second `run()` on the same repository state. Assert the account projection equals the
   uninterrupted run's, `processedPins` and the counters are the sums rather than the last run's, no
   pin is duplicated, no issue row is duplicated, and no orphan is left in the image store.
5. **The fence** (use-case unit). A fake repository returning a different `runToken` from the third
   pin on: two pins written, the third refused, the state left as the canceller wrote it.
6. **Cancellation** (integration for `AWAITING_ARCHIVE` and `PENDING`, unit for `RUNNING`). The
   deterministic cases assert `CANCELLED`, no task, and no bytes on disk; the `RUNNING` case is the
   fence test above, since a real worker finishes a small archive before a test can act. That limit
   is stated rather than papered over with a sleep.
7. **Per-line anomalies** (integration, one archive): an entry path with `../`, a truncated JSONL
   line, a pin declaring an absent image, a text file renamed `.jpg`, a pin with no image, a board
   name of 300 characters, a pin with 200 tags. Assert the import reaches `COMPLETED`, the good pins
   exist, and each of `ENTRY_PATH_INVALID`, `LINE_MALFORMED`, `MEDIA_ENTRY_MISSING`,
   `MEDIA_UNREADABLE`, `PIN_HAS_NO_MEDIA`, `FIELD_INVALID` appears exactly once.
8. **Rejected archives** (use-case unit), one per failure code: `formatVersion` 2, absent manifest,
   unreadable ZIP. Each asserts `FAILED`, the code, `processedPins = 0`, and nothing created.
9. **Bounds** (adapter unit): an entry count past `max_entries`, a metadata entry past
   `max_metadata_bytes`, a single JSONL line past `max_line_bytes`. Each refuses without allocating
   the whole entry, which is asserted by the refusal arriving before the stream is exhausted.
10. **Oversize and over-pixel media** (use-case unit) with `imageStore.stage` throwing
    `ImageTooLargeException` and the probe throwing the pixel refusal, following `SetPinImageTest`'s
    existing shape rather than building a 30 MiB fixture.
11. **Lying manifest** (integration): `image.mimeType` says `image/jpeg` over PNG bytes; the stored
    media type is `image/png`. A wrong `sha256` in the same archive yields `MEDIA_DIGEST_MISMATCH`
    while the pin is still created.
12. **Ambiguous digest** (integration): two existing pins on one medium; assert no pin row, no image
    row, no promoted object, no staged temp file, and exactly one `MEDIA_AMBIGUOUS`.
13. **Non-empty account** (integration), with case differing: the account holds tag `voyage` and
    board `Summer`, the archive carries `Voyage` and `summer`. Assert no tag and no board created,
    `skippedTags` and `skippedBoards` at one each, and the existing board's `updatedAt`, description
    and membership set unchanged.
14. **Recycled board holds its name** (integration): the account has `Summer` in the recycle bin, the
    archive an active `Summer`. Assert nothing created, `NAME_TAKEN_BY_RECYCLED` recorded, and the
    recycled board untouched.
15. **Board constraint at all three sites** (integration): `POST` with a taken name, `PUT` renaming
    onto a taken name, and restoring from the recycle bin. Each returns `409
    BOARD_NAME_ALREADY_EXISTS`, never a 500.
16. **Report cap** (use-case unit) with the limit injected: 501 anomalies store 500 rows, report
    `issueCount = 501`, and set `issueDetailTruncated`; 499 leave it false.
17. **Chunked upload** (integration): three chunks, a replayed offset refused with the current
    length, a chunk carrying the total past the maximum refused with `413`, and a resumed upload
    completing from the reported length.
18. **The sweep** (integration, calling the bean directly rather than waiting on the interval): a
    stale `AWAITING_ARCHIVE` row becomes `ABANDONED` with its partial upload gone; a terminal row's
    bytes are deleted once and not re-deleted on a second run; an orphaned staged file is swept.
19. **Account deletion** (integration): an account with one `COMPLETED` and one `AWAITING_ARCHIVE`
    import; assert no rows in either table and nothing left in `imports.data_dir`.

## 14. Risks and accepted trade-offs

- **A pin with no medium does not survive the round trip.** Pending and failed downloads, and images
  the exporter could not write, are reported and dropped. Making them travel needs the export to
  carry `ImageDownload`: a backlog item.
- **Two pins sharing one medium in the archive import as one.** The second line finds the pin the
  first just created and counts as a skip, losing its description, tags and memberships. Scenario 1
  pins this so it is visible rather than discovered.
- **Restoring recovers less than the word suggests.** Hard-deleted rows come back; a recycled pin
  stays recycled (its digest matches, so it is skipped), an edited description keeps the edit, and a
  removed board membership is not re-added.
- **No step-up, and the accepted worst case is written here**: a stolen bearer token can fill the
  account with unwanted rows, which are removable only one request at a time, and can take board and
  tag names the owner then cannot reuse until they delete them.
- **An import is not atomic and cancellation leaves partial state**, stated in the API documentation
  and not only here.
- **A long import holds one of four worker permits for hours**, and every write in the instance
  queues behind one SQLite connection. Several concurrent imports can starve `pin.download` and
  `account.export`, which is why the import enqueues at priority `-1` while everything else sits at
  `0`. No per-kind fairness exists beyond that single number.
- **The report cap can hide detail**, flagged rather than silent.
- **Lowering `images.max_file_bytes` later makes previously importable archives partly
  un-importable**, as `MEDIA_TOO_LARGE`.
- **`FIELD_INVALID` bounds are restated, not shared.** Until the bounds move into the entities, the
  DTOs and this walk can drift apart. The backlog carries the consolidation.
- **`USER_GONE` leaves the archive bytes behind.** The refusal happens before the run is claimed, so
  step 8 never runs and the sweep reclaims them on its next pass, like any other orphan.
- **The metadata walks have no cursor.** A resumed attempt re-walks `tags.jsonl` and `boards.jsonl`
  in full with an empty tally, so every line already imported is counted a second time as a skip.

## 15. References

- Export format contract: `docs/specs/2026-07-22-user-data-export.md` section 4. Two of its sentences
  have since become false (task-queue logging, hand-written index migrations); it is a dated document
  and keeps them.
- Unique constraint outcomes and the empty hand-written allowlist: `docs/adr/0009-unique-index-named-outcomes.md`.
- Soft-delete read isolation, and why a read names its state: `docs/adr/0008-structural-soft-delete-read-isolation.md`.
- Scheduler injection: `docs/adr/0004-inject-schedulers-by-type.md`.
- Quarkus body size limit: <https://quarkus.io/guides/http-reference#quarkus-vertx-http_quarkus-http-limits_quarkus-http-limits-max-body-size>
- GDPR Article 20, which grants a right to receive and transmit personal data and imposes no duty on
  a receiving controller to accept an import: <https://gdpr-info.eu/art-20-gdpr/>
