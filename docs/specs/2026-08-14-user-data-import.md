# User data import (portability)

Date: 2026-08-14
Status: draft, pending approval
Depends on: the export archive format (`docs/specs/2026-07-22-user-data-export.md` §4, `formatVersion` 1),
the task queue (`EnqueueTask`, `CancelTask`, `TaskHandler`, `renewLease`), `ImageStore`, `ImageProbe`,
the pin / board / tag / image repositories, `TransactionRunner`, `Clock`. One new adapter-only
dependency (`jackson-module-kotlin`, via the Quarkus BOM).

## 1. Goal

Let an authenticated user re-create their pins, boards, tags and images on this instance from an
export archive. Export shipped the first half of the "User data import / export (portability)"
backlog item; this is the second.

Three user scenarios are served, and after the design below they are **one code path**, not three:

- **Moving house.** A user opens an account on another instance, or reinstalls their own, and pours
  their archive into an empty account.
- **Merging accounts.** A user pours an archive into an account that already holds data.
- **Restoring.** A user replays last week's archive after deleting things by mistake.

## 2. Scope

**In scope:**

- **Create an import** (`POST /api/v1/me/imports`), returning `202` and an import resource awaiting
  its archive.
- **Upload the archive** (`PUT /api/v1/me/imports/{id}/archive`), several gigabytes, streamed to
  disk, enqueuing the `account.import` task.
- **Track** (`GET /api/v1/me/imports`, `GET /api/v1/me/imports/{id}`) with a progress figure, and
  **read the report** (`GET /api/v1/me/imports/{id}/issues`).
- **Cancel** (`DELETE /api/v1/me/imports/{id}`), with the partial state it leaves.
- **Asynchronous replay** in the worker: read the archive, create what is missing, skip what is
  already there, record every anomaly.
- **Two uniqueness constraints the import needs**: `(user, tag.name)` and `(user, board.name)`, both
  case insensitive. The board one changes a public contract (§12).
- **Account deletion erases imports** (rows, issues and archive bytes).

**Out of scope (deferred to the backlog):**

- **Override mode.** Rejected in §3, with the reason.
- **Selective import** (one board, skip the recycle bin), **partial export**, **merging metadata
  onto an existing pin**, **resumable chunked upload**, **per-account storage quotas**, **importing
  pins that carry no media**.

## 3. Key decisions

- **The import is additive and never destructive.** It creates what is missing and leaves everything
  else alone. It never modifies, deletes or re-parents an existing row.
- **Override mode is rejected, and this is the load-bearing decision of the whole design.** An
  earlier design had the server compute a plan of overwrites, present it, and apply only the
  pre-approved set. It was dropped because no user scenario needs it: moving house lands in an empty
  account, merging wants addition, and restoring wants what was lost back rather than what survived
  replaced. Everything downstream follows from this: no step-up re-authentication (§9), no two-phase
  approval, no transactional rollback, and idempotence for free (§8).
- **A conflict means skip, and skipping is reported.** The alternatives (complete the existing
  entity, create a duplicate) are backlog items, not v1 behaviour.
- **Identity is a natural key, never an archive UUID.** Archive identifiers are read and discarded;
  every created row gets a fresh `randomUUID()`. Reusing them would let a user choose their own
  primary keys, and would make the same archive un-importable by two users on one instance, which is
  a case this design supports.
- **The natural keys are: tag by name, board by name, pin by the SHA-256 of its media.** All name
  comparisons are case insensitive.
- **A pin with no image in the archive is skipped and reported.** A pin is metadata over a medium;
  with no medium there is nothing to anchor it to, and no identity to make a re-import idempotent.
  The cost is stated as an accepted limit (§14): a pin whose image download was pending or had
  failed does not survive the round trip, and neither does one whose bytes the exporter could not
  write.
- **When the SHA-256 matches more than one existing pin, the import does nothing and reports it.**
  Nothing today forbids two pins on the same medium (`uq_images_pin_id` binds a pin to at most one
  image, but no constraint binds a medium to at most one pin), so the key can return several rows.
  Inventing a winner would be arbitrary; a visible refusal is not. Whether the product should forbid
  duplicate media at all is a backlog question, not this lot's.
- **The archive's timestamps are restored**, clamped to the import instant when they are in the
  future. This bends the project invariant that instants come from the `Clock` port, deliberately
  and narrowly: the bend covers only what the import restores, never what it invents (identifiers,
  the import's own timestamps). Without it, moving house re-dates three thousand pins to the same
  second and destroys the chronology the export spec paid to preserve (its §3).
- **Recycled pins and boards are imported and land in the recycle bin**, carrying their `deletedAt`.
- **The manifest is never trusted for anything that has a consequence.** Every image is staged and
  probed exactly like an upload: the stored `mimeType`, dimensions and `animated` flag come from
  `ImageProbe`, never from the archive. `ImageController.serveOriginal` serves the stored media type,
  so copying an unverified one is stored cross-site scripting by the shortest path. The manifest is
  read for two harmless things: knowing which entries to expect, and displaying a progress figure.
- **Verifying the SHA-256 is free and is done.** `ImageStore.stage` already computes size and digest
  in one pass over bytes that must be written anyway; comparing the result costs a string equality.
- **The digest is computed before the probe, and the probe runs only for new media.** Reading and
  hashing is cheap, a libvips probe is not. A re-import of three thousand known images therefore
  costs three thousand reads and zero probes.
- **Upload is its own request.** `POST` creates the resource, `PUT` pours the bytes. The gain is
  refusing early: a user on a slow link learns that an import is already running before spending
  forty minutes uploading, not after. Note what this does **not** buy: `quarkus.http.limits.max-body-size`
  is a single server-wide setting (`ServerLimitsConfig`, run-time phase) with no documented per-route
  override, so accepting multi-gigabyte archives raises the limit for every route. The image path is
  unaffected in practice: its 30 MiB bound is enforced by the use case, and `application.properties`
  already states the framework is only a backstop there.
- **The archive is destroyed when the import ends**, whatever the outcome.
- **The import is idempotent, and that is what makes recovery correct.** Replaying an archive from
  the start creates what is missing and skips what exists, which is exactly what it does against a
  non-empty account. The cursor (§8) is therefore an optimisation and a progress source, not a
  correctness mechanism.
- **A single bad entry never fails the import.** It is skipped, reported, and the walk continues.
  A tar-style all-or-nothing failure would waste 2999 successful pins over one truncated file, and
  since nothing is transactional across the archive the account would be left half filled under a
  status that says "failed", which is the worst of both.
- **The report details anomalies and counts the rest.** A re-import of a known archive produces
  three thousand "already present" lines that teach nothing; a broken one produces three anomalies
  that matter. Detail is capped (§9) so the table cannot grow without bound.

## 4. What the importer reads

Input contract: the archive format of `docs/specs/2026-07-22-user-data-export.md` §4, `formatVersion`
1. Any other value is refused (§10).

| Entry | Read for | Ignored |
|---|---|---|
| `manifest.json` | `formatVersion` (a decision), `counts.pins` (progress display only) | `entries`, `generator`, digests, `excluded` |
| `tags.jsonl` | `name`, `createdAt` | `id` |
| `boards.jsonl` | `name`, `description`, `createdAt`, `updatedAt`, `deletedAt` | `id` |
| `pins.jsonl` | `description`, `sourceContextUrl`, `sourceMediaUrl`, timestamps, `deletedAt`, `tags[].name`, `boards[].name`, `image.path` | `image` metadata other than `path`, all `id` fields |
| `images/*` | the bytes | the names, except to locate an entry |
| `user.json`, `README.md` | nothing | everything |

`user.json` is not imported: the target account already exists with its own name and creation date.

**Entry paths are validated, never trusted.** A pin's `image.path` is matched against
`images/[A-Za-z0-9._-]+` and rejected otherwise. Structurally, path traversal cannot reach the disk
anyway: an archive entry name is only ever used to look up an entry, and every write goes through
`ImageStore` under a storage key the import builds itself from fresh identifiers. The check exists so
a malformed archive is reported rather than silently skipped.

## 5. Domain and ports

```kotlin
data class UserDataImport(
    override val id: UUID,
    val userId: UUID,
    val state: UserDataImportState,
    val requestedAt: Instant,
    val taskId: UUID? = null,
    val archiveUploadedAt: Instant? = null,
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
    val createdTags: Int = 0,
    val issueCount: Int = 0,
    val failureCode: String? = null,
) : Identifiable
```

`UserDataImportState`: `AWAITING_ARCHIVE`, `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`,
`ABANDONED` (created but never fed an archive). `isActive` covers the first three, and is what the
one-import-at-a-time rule tests.

`processedPins` is the cursor: the number of `pins.jsonl` lines settled. `announcedPins` is
`manifest.counts.pins`, kept only to render progress, and a mismatch with the real line count is not
an error.

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
`MEDIA_TOO_LARGE`, `MEDIA_AMBIGUOUS` (several existing pins share the digest), `LINE_MALFORMED`,
`ENTRY_PATH_INVALID`. "Already present" is **not** an issue kind: it is `skippedPins`.

### `ImportArchiveStore` port

```kotlin
interface ArchiveSource : AutoCloseable {
    fun entryNames(): Set<String>
    fun <T : Any> readJson(name: String, type: Class<T>): T?
    fun <T : Any> readJsonLines(name: String, type: Class<T>, block: (Sequence<ArchiveLine<T>>) -> Unit)
    fun openEntry(name: String): InputStream?
}

interface ImportArchiveStore {
    fun stage(source: InputStream): StagedFile
    fun promote(staged: StagedFile, storageKey: String)
    fun open(storageKey: String): ArchiveSource
    fun delete(storageKey: String)
    fun discard(staged: StagedFile)
    fun discardOrphanedStagedFiles(olderThan: Instant): Int
    fun forEachStorageKeyOnDisk(block: (Sequence<String>) -> Unit)
}
```

Mirror of `ExportArchiveStore`, same stage-then-promote shape, same loan contract on the lazy
sequence (the adapter owns the entry stream and closes it when `block` returns). `ArchiveLine<T>`
carries the line number and either the parsed value or the parse failure, so a malformed line becomes
a reported issue instead of an exception that ends the walk.

`readJsonLines` streams: a `pins.jsonl` larger than memory is read line by line, and the sequence is
skipped forward to the cursor rather than materialised.

**Deserialization needs `jackson-module-kotlin`**, which is not on `api-storage-filesystem`'s
classpath today (verified: `./gradlew :api-storage-filesystem:dependencies --configuration runtimeClasspath`
lists only `jackson-core`, `-databind`, `-annotations` and `-datatype-jsr310`). A Kotlin data class has
no no-argument constructor, so Jackson alone cannot instantiate one. The module is registered on a
**reader-only `ObjectMapper`**; the export's mapper is built explicitly
(`ObjectMapper().registerModule(JavaTimeModule())`, no `findAndRegisterModules()`), so the written
format cannot move, and `ExportContentGoldenJsonTest` remains the proof.

### Repository ports

`UserDataImportRepositoryInterface`: `save`, `findById`, `findAllForUser(userId, cursor, pageSize)`,
`findActiveForUser`, `findAbandonedBefore(instant)`, `findAllImportIdsForUser`, `deleteAllForUser`.

`UserDataImportIssueRepositoryInterface`: `save`, `findAllForImport(importId, cursor, pageSize)`,
`countForImport`, `deleteAllForImport`, `deleteAllForUser`.

`ImageRepositoryInterface` gains **`findPinIdsByContentHashForUser(user, contentHash): List<UUID>`**.
It returns a list rather than a nullable single row, because that is what makes the ambiguous case
(§3) visible instead of arbitrary.

`TagRepositoryInterface.findUserTagByName` becomes case insensitive (§12).

## 6. Use cases

- **`UserDataImportCreator.create(user)`**: refuses when an active import exists
  (`IMPORT_ALREADY_IN_PROGRESS`), inserts an `AWAITING_ARCHIVE` row. No step-up factor (§9).
- **`UserDataImportArchiveReceiver.receive(user, importId, bytes)`**: owner check, state check
  (`AWAITING_ARCHIVE` only), stages the bytes, promotes them under a key derived from the import id,
  writes `PENDING` with `storageKey`, `byteSize` and `archiveUploadedAt`, enqueues `account.import`
  and stores the returned task id. The storage key is written **before** the promote, same reasoning
  as the export builder: a key derived from the id keeps the bytes reclaimable even if the row is
  never written again.
- **`UserDataImportRunner.run(importId, isLastAttempt, renewLease)`**: the worker path (§8).
- **`UserDataImportGetter`**: by id (owner checked) and the user's paginated history.
- **`UserDataImportIssueLister.list(user, importId, cursor, pageSize)`**: owner checked.
- **`UserDataImportCanceller.cancel(user, importId)`**: owner check; `AWAITING_ARCHIVE` and
  `PENDING` cancel the task and move to `CANCELLED`; `RUNNING` sets a cancellation flag the runner
  observes between two pins, so the walk stops at a settled boundary; terminal states are a no-op.
  The rows already written stay: an import is not a transaction, and the report says where it stopped.
- **`ReapAbandonedUserDataImports.reap()`**: moves `AWAITING_ARCHIVE` rows older than
  `imports.upload_grace` to `ABANDONED` so one forgotten upload cannot block the account forever,
  deletes the archive bytes of every terminal row, and sweeps orphaned staged files.
- **`UserDataImportTask`**: `KIND = "account.import"`, `MAX_ATTEMPTS = 3`.

## 7. REST surface

All endpoints `@Authenticated` and owner-scoped (`403` for a non-owner, `404` for an unknown id).

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/v1/me/imports` | `202`, state `AWAITING_ARCHIVE` |
| `PUT` | `/api/v1/me/imports/{id}/archive` | `application/zip`, streamed; `202` |
| `GET` | `/api/v1/me/imports` | Paginated history (cursor) |
| `GET` | `/api/v1/me/imports/{id}` | State, counters, progress |
| `GET` | `/api/v1/me/imports/{id}/issues` | Paginated report |
| `DELETE` | `/api/v1/me/imports/{id}` | `204` |

Response DTO: `id`, `state`, `requestedAt`, `startedAt`, `completedAt`, `byteSize`, `formatVersion`,
`announcedPins`, `processedPins`, `createdPins`, `skippedPins`, `createdBoards`, `createdTags`,
`issueCount`, `failureCode`.

The archive body is consumed as an `InputStream` and streamed straight to `ImportArchiveStore.stage`,
never buffered. `quarkus.http.limits.max-body-size` is raised in `application.properties` with a
comment naming what it protects and what it no longer protects.

## 8. The `account.import` task

`UserDataImportTaskHandler` delegates to the runner with
`isLastAttempt = context.attempt >= context.maxAttempts`, the same comparison the export handler uses.

1. Load the import; return if absent or not `PENDING`/`RUNNING`.
2. Load the user; if gone or tombstoned, `FAILED` with `USER_GONE` and `PermanentTaskException`.
3. Open the archive. Unreadable, no manifest, or `formatVersion != 1`: `FAILED` with the matching
   code and `PermanentTaskException`. Retrying cannot help.
4. Mark `RUNNING`, stamp `startedAt` and `announcedPins`.
5. Walk `tags.jsonl`: find-or-create by name, restoring `createdAt` on creation.
6. Walk `boards.jsonl`: find-or-create by name, restoring description and timestamps on creation,
   and soft-deleting the board when the archive carries a `deletedAt`.
7. Walk `pins.jsonl` from `processedPins`, one pin at a time (below). `renewLease()` after each.
8. `COMPLETED`, stamp `completedAt`, delete the archive bytes.

Per pin, in order:

1. No `image`: issue `PIN_HAS_NO_MEDIA`, advance, next.
2. `image.path` malformed: `ENTRY_PATH_INVALID`. Entry absent from the archive: `MEDIA_ENTRY_MISSING`.
3. `imageStore.stage(entry, images.max_file_bytes)`: reads, sizes and digests in one pass. Over the
   bound: `MEDIA_TOO_LARGE`.
4. `findPinIdsByContentHashForUser`: one hit means already present, discard the staged file, count it
   in `skippedPins`; more than one means `MEDIA_AMBIGUOUS`, same discard.
5. Zero hits: `imageProbe.probe` (failure gives `MEDIA_UNREADABLE` and a discard), then promote the
   bytes, then in **one transaction** create the pin with the archive's timestamps, create the image
   row, resolve tags and boards by name, apply `deletedAt`, and advance the cursor and counters.
   Promote-then-transaction with compensation on failure is `SetPinImage`'s existing pattern.

**The cursor and the counters move in the same transaction as the pin.** They could be written
periodically instead, since idempotence would repair any replayed lines, but replayed lines would be
counted twice (once created, once skipped) and the report would drift after every crash. One extra
column write per pin is negligible next to a libvips probe and an image write.

`TaskProcessor` swallows handler exceptions without logging, so the import row's state, `failureCode`
and issues are the operator-visible truth, exactly as for exports.

## 9. Quotas, configuration and lifecycle

- **No step-up re-authentication.** The export requires it because it turns a pin-by-pin grind into
  one file holding everything; an import exfiltrates nothing, and since override was dropped it
  cannot destroy anything either. The worst a stolen token achieves is adding unwanted data and
  consuming disk. **If override ever returns, step-up returns with it.**
- **One active import per user**, enforced in the use case and by a partial unique index
  (`unique(user_id) where state in ('AWAITING_ARCHIVE','PENDING','RUNNING')`), the adapter
  translating the violation into `ImportAlreadyInProgressError`, mirroring the export precedent.
- **No minimum interval between two imports**: someone repatriating three old accounts must be able
  to chain them.
- **No free-space precheck.** The export checks a fixed margin before building, but an importer
  cannot estimate what it will write without believing the manifest, which it does not. A disk-full
  failure mid-walk is therefore treated as **transient**: the task retries, the cursor resumes where
  it stopped, and an operator who frees space in between loses nothing. After the last attempt it
  ends `FAILED` with `DISK_FULL`.

| Key | Default | Meaning |
|---|---|---|
| `imports.data_dir` | `/var/lib/pinry/imports` | Uploaded archives (a new volume, to document in deploy) |
| `imports.upload_grace` | `PT1H` | Age past which an `AWAITING_ARCHIVE` row is abandoned |
| `imports.sweep_interval` | `PT1H` | How often the sweep runs |
| `imports.staged_file_max_age` | `PT6H` | Age past which a staged temp file is orphaned |
| `imports.report_detail_limit` | `500` | Issues stored per import; beyond it only `issueCount` grows |

The sweep runs on its own single-thread scheduler, injected by type (`PeriodicScheduler`), following
`docs/adr/0004`.

## 10. Errors

| Code | Status | When |
|---|---|---|
| `IMPORT_ALREADY_IN_PROGRESS` | `409` | An active import already exists |
| `IMPORT_DOES_NOT_EXIST` | `404` | Unknown id |
| `IMPORT_INSUFFICIENT_PERMISSIONS` | `403` | Not the owner |
| `IMPORT_NOT_AWAITING_ARCHIVE` | `409` | `PUT` on an import that already has its archive, or is terminal |
| `BOARD_NAME_ALREADY_TAKEN` | `409` | §12, and it applies to `POST /api/v1/boards` too |

Failure codes on the row (not HTTP): `USER_GONE`, `ARCHIVE_UNREADABLE`, `MANIFEST_MISSING`,
`UNSUPPORTED_FORMAT_VERSION`, `DISK_FULL`.

Each new code is an arm of `BaseErrorMapper`'s exhaustive `when` and lands in the same commit as the
enum value, since the `when` has no `else`.

## 11. Persistence and migration

Migration `1.20` creates `user_data_imports` and `user_data_import_issues`, and adds the two unique
indexes of §12. The partial unique index is hand-written in its own migration file so a regeneration
cannot silently drop it, following the export precedent. States are stored as plain `String` columns
converted in the mapper, following `TaskModel` and `UserDataExportModel`.

Unique indexes are declared through `@Index(definition = "create unique index ...")`, never
`unique = true`, and each one gets its row in `UniqueConstraintOutcomeTest` naming the answer a
client receives.

## 12. The two uniqueness constraints, and the contract they change

The import needs a name to be an identity. Today it is not one.

- **Tags.** `TagCreator.findOrCreate` already reads before writing, so `(user, name)` unique changes
  nothing observable and only closes the race the project's own invariant forbids leaving open
  (`docs/adr/0009`). `findUserTagByName` must become case insensitive in the same commit, otherwise
  the read looks for `Voyage`, misses `voyage`, and the insert hits the index.
- **Boards.** `BoardCreator.create` checks nothing: `POST /api/v1/boards` with a name already taken
  currently succeeds and creates a homonym. Under the constraint it returns `409`. **This is a
  breaking change to a public contract**, taken deliberately: alpha status allows it, no instance is
  deployed, and an import cannot use a name as an identity while the name is not one.

Both indexes are `collate nocase`, following `ix_users_name_nocase`.

## 13. Testing strategy

TDD, 100% branch coverage per package. Beyond the mechanical unit tests, these carry the real risk:

1. **Round trip against a real worker**: seed pins (one recycled), a recycled board holding a pin,
   tags and real images; export, wipe, import, and assert the account is equivalent. This is the test
   that would catch a format misreading, and it is written early, not last.
2. **Import into a non-empty account**: existing tag, existing board, existing pin on the same
   medium. Assert nothing existing moved, and that the report counts the skips.
3. **Idempotence**: importing the same archive twice creates nothing the second time and reports
   every pin as skipped.
4. **Resumption**: kill the runner mid-walk, re-run the task, assert the final state equals the
   uninterrupted one and that no pin is duplicated.
5. **Hostile archive**: an entry path with `../`, a manifest announcing a `formatVersion` of 2, a
   truncated JSONL line, an image entry declared by a pin but absent, a text file renamed `.jpg`.
   Each produces its issue kind, and the import still completes.
6. **Lying manifest**: an archive whose `image.mimeType` says `image/jpeg` over PNG bytes, asserting
   the stored media type comes from the probe.
7. **Ambiguous digest**: two existing pins on one medium, assert `MEDIA_AMBIGUOUS` and no write.
8. **Timestamps**: an archive with a `createdAt` in the future, asserting the clamp.
9. **Cancellation mid-walk**: state `CANCELLED`, pins written so far still present, archive gone.
10. **Board name collision** through `POST /api/v1/boards`, pinning the new `409`.

## 14. Risks and accepted trade-offs

- **A pin with no media does not survive the round trip.** Pending and failed downloads, and images
  the exporter could not write, are all reported and dropped. Making them travel needs the export to
  carry `ImageDownload`, which is a backlog item.
- **The report drifts if the detail cap is hit**: past `imports.report_detail_limit`, `issueCount`
  keeps counting but the detail stops. The DTO says so with a flag rather than lying by omission.
- **An import is not atomic and cancellation leaves partial state.** Stated in the API documentation,
  not only here.
- **Uploading several gigabytes is assumed cheap** (local network or fibre). A failed import means
  re-uploading, since the archive is destroyed on termination.
- **Raising `max-body-size` server-wide** removes the framework backstop from every route. Accepted
  under the project's stated assumption that instance users are not malicious, which is itself a
  recorded limit; invitation codes and quotas are backlog items.
- **N+1 on a large import**: one digest lookup, one probe and several inserts per pin. An import is
  rare, asynchronous, and one worker among several. Same trade-off the export took.

## 15. References

- Export format contract: `docs/specs/2026-07-22-user-data-export.md` §4.
- Unique constraint outcomes: `docs/adr/0009-unique-index-named-outcomes.md`.
- Scheduler injection: `docs/adr/0004-inject-schedulers-by-type.md`.
- GDPR Article 20 (portability includes re-import): <https://gdpr-info.eu/art-20-gdpr/>
- Quarkus body size limit (`ServerLimitsConfig`, `quarkus.http.limits.max-body-size`):
  <https://quarkus.io/guides/all-config>
