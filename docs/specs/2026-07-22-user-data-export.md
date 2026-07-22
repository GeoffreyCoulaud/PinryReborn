# User data export (portability)

Date: 2026-07-22 (revised 2026-07-22 after adversarial review)
Status: approved design, pending implementation plan
Depends on: the task queue (`EnqueueTask`, `CancelTask`, a new `TaskHandler`, and a **new
`renewLease` queue operation**), the step-up re-authentication brick (`Reauthenticator`,
`X-Reauthentication`), `ImageStore`, the pin / board / tag / image repositories, `TransactionRunner`,
`Clock`. Two new adapter-only dependencies (`jackson-databind` + `jackson-datatype-jsr310`, both via
the Quarkus BOM).

## 1. Goal

Let an authenticated user download **all** of their data as a single, self-contained archive, so they
stay in control of it and are never held hostage by this instance. This is the "User data export /
import (portability)" P1 item in `docs/backlog.md`, reduced here to its export half.

The archive is **self-contained**: image bytes travel inside it. An export that only lists metadata
and leaves the pixels behind an authenticated API would be worthless the day the user loses their
account on this instance, which is precisely the day portability matters.

The archive format is designed as the **input contract of the future importer**, even though no
importer ships here.

## 2. Scope

**In scope:**

- **Request an export** (`POST /api/v1/me/exports`), behind step-up re-authentication, returning
  `202` and an export resource.
- **Asynchronous archive building** in the worker (`account.export` task): a ZIP holding a manifest,
  a human-readable README, the profile, JSONL collections (pins, boards, tags) and the original
  image bytes.
- **Track and download**: `GET /api/v1/me/exports` (paginated history), `GET /api/v1/me/exports/{id}`
  (state), `GET /api/v1/me/exports/{id}/download` (bytes, authenticated, owner-scoped, range-aware).
- **User-initiated destruction** (`DELETE /api/v1/me/exports/{id}`): cancels a pending export or
  destroys a ready one immediately.
- **Retention and quotas**: 7-day retention, at most one live archive per user, a minimum delay
  between two exports, and a free-space check before building.
- **Automatic purge** of expired archives, shipped in v1, not deferred to a backlog item.
- **Account deletion erases exports** (rows and bytes).
- **Creation timestamps promoted into the domain** for `User`, `Pin`, `Board` and `Tag`.
- **Two pre-existing defects this feature would otherwise trip over** (§15): a cursor pagination that
  can loop forever, and a task queue that re-claims a task whose handler never returns.

**Out of scope (deferred):**

- **Import.** Stays a backlog item. This spec only commits to a versioned format an importer can read.
- **Encryption of the archive**, **email delivery / signed links**, **scheduled exports**, **exporting
  renditions**, **selective/partial export**. All rejected in §3 or simply not needed yet.

## 3. Key decisions

- **Asynchronous, on the existing task queue, not a synchronous stream.** A synchronous export holds
  an HTTP thread for minutes, dies on any proxy timeout, and offers no resumption, no announced size
  and no verifiable digest. Asynchronous building matches every reference implementation (Google
  Takeout, GitHub user migrations, Mastodon, Discourse) and reuses machinery this codebase has.
- **Password hashes and session tokens are never exported.** They are secrets, not user-useful data.
- **Recycle-bin content is exported, and so are its links.** Soft-deleted pins and boards are still
  restorable by their owner, so they are still the user's data. Critically, `softDeleteBoard`
  **keeps** its pin memberships in the database while `PinRepository.getBoardsForPin` filters them
  out of the API view. Exporting the API view would therefore silently drop those links, and a user
  restoring a board from their archive would find it empty. The export reads memberships **without
  that filter**, mirroring how recycled pins are already exported with their `deletedAt`.
- **Creation timestamps are promoted into the domain.** Without them the archive loses the chronology
  and a future import would re-date everything.
- **ZIP, not tar.gz**, opened by a double-click everywhere, with random access per entry. Mastodon
  moved from tar.gz to ZIP in 4.2 for the same reasons.
- **Image entries are written `DEFLATED` at level 0, not `STORED`.** Verified experimentally on
  Temurin 25: `STORED` fails with "STORED entry missing size, compressed size, or crc-32" unless both
  are known up front, which would force reading every image twice; level 0 streams in one pass with a
  ratio of 1.00015.
- **ZIP64 is required and verified.** A classic ZIP caps at 4 GB and 65535 entries; a few thousand
  pins reach the entry cap first. Verified: 65 600 entries write in 150 ms and re-read correctly.
- **JSONL for collections, JSON for singletons**, so both the writer and a future importer work in
  constant memory and a truncated file loses only its last line.
- **Tags and boards are denormalized into each pin (id and name)**, so a reader needs no manual join.
- **A `formatVersion` ships in v1**, plus **per-entry SHA-256** in the manifest and a whole-archive
  SHA-256 exposed by the API.
- **The manifest is written last** (it carries the other entries' digests). ZIP entry order is
  irrelevant to readers.
- **The images are written BEFORE `pins.jsonl`, and each pin references only images actually
  written.** A ZIP holds one open entry at a time, so image bytes cannot be emitted while
  `pins.jsonl` is open: two walks are unavoidable. Writing the pins first would let a pin declare
  `image.path` for an image deleted between the two walks, leaving a **dangling reference** in a
  format that is meant to be machine-read. Writing the images first makes that incoherence
  structurally impossible: the second walk emits a path only if it is in the set just written.
- **The archive documents its own exclusions** (DTI transparency principle): the README and manifest
  list what is deliberately absent and why.
- **No archive encryption**; confidentiality comes from access control on the download.
- **Authenticated download, no secret-bearing link.** A URL carrying a secret leaks through history,
  proxy logs and referrers. The web client fetches with its bearer token and hands the blob over.
- **Step-up re-authentication to request an export**, verified **inside the use case**, exactly as
  `AccountDeleter` does. A stolen session token can already read data pin by pin; an export turns
  that grind into one file containing everything.
- **The ZIP format stays an implementation detail of the adapter**, which speaks in archive entries;
  ZIP and Jackson mechanics live in `api-storage-filesystem`, so `api-usecases` gains no
  serialization dependency.
- **…which is why the effective media type and size travel through the layers.** The port declares
  its `ArchiveFormat`, the builder persists the media type and extension on the row, and the
  controller reads them back, mirroring `ImageController.serveOriginal`, which already serves
  `Content-Type` and `Content-Length` from the stored `Image`. An archive is always served as what it
  actually is, even if the adapter changes later.
- **Enumeration is paginated where pagination exists, and that is a memory bound, not a query
  bound.** Pins are walked by cursor; boards and tags have only unpaginated readers and are read
  whole (bounded by a user's board and tag count, which is small). **This design does not avoid
  N+1**: `PinRepository` already issues two queries per pin for its tags and boards, the export adds
  one for the image and one for the unfiltered memberships, and the second walk doubles the pin
  reads. On a very large account this is hundreds of thousands of SQLite queries. Accepted: an export
  is rare, asynchronous, and one worker among several. A dedicated bulk read path is a future
  optimisation, recorded in §16.
- **`EXPIRED`, `DELETED`, `SUPERSEDED` and `FAILED` are distinct terminal states**, because "the
  system purged it", "I destroyed it", "I replaced it" and "it failed" are different answers to the
  same user question. They behave identically at download time (`410`).
- **Rows are kept after purge; only the bytes are destroyed**, which gives the user an audit trail
  and avoids a second sweep.

## 4. Archive format, version 1

File name: `<ISO date>-pinry-export-<username>.<ext>`, e.g. `2026-07-22-pinry-export-alice.zip`. The
date leads so archives sort chronologically; date only, since `:` is illegal in Windows file names.
The extension comes from the stored `fileExtension`, never a literal.

```
2026-07-22-pinry-export-alice.zip
├── README.md
├── manifest.json
├── user.json
├── pins.jsonl
├── boards.jsonl
├── tags.jsonl
└── images/
    └── <imageId>.<ext>
```

All timestamps are ISO-8601 UTC. All ids are UUID strings. Absent values are `null`, never omitted.

The adapter's Jackson instance is configured **explicitly and locally**: `JavaTimeModule` registered,
`WRITE_DATES_AS_TIMESTAMPS` disabled, no configuration inherited from the REST layer's mapper. The
field names are a published contract, so a **golden-JSON test** pins the serialized shape of every
`Exported*` type; without it, a Jackson upgrade or an added property silently changes the format.

### `manifest.json`

```json
{
  "formatVersion": 1,
  "generator": { "name": "pinry-reborn", "version": "<application version>" },
  "exportId": "8f14e45f-...",
  "createdAt": "2026-07-22T10:15:30Z",
  "expiresAt": "2026-07-29T10:15:30Z",
  "user": { "id": "3fa85f64-...", "name": "alice" },
  "counts": { "pins": 1234, "boards": 12, "tags": 90, "images": 1180 },
  "entries": [ { "path": "pins.jsonl", "byteSize": 918273, "sha256": "..." } ],
  "excluded": [
    { "what": "password hashes", "why": "secrets; useless to you, dangerous if this archive leaks" },
    { "what": "session tokens", "why": "secrets; expired and meaningless outside this instance" },
    { "what": "image renditions", "why": "derived from the original bytes, regenerable" }
  ]
}
```

`counts` come from **counters incremented while writing**, never from re-iterating a sequence: a
`sequence {}` is lazy but re-iterable, so counting by a second pass would silently re-run the whole
pagination.

### `pins.jsonl` (one object per line)

```json
{
  "id": "...", "description": "...",
  "sourceContextUrl": "https://example.org/article",
  "sourceMediaUrl": "https://example.org/image.jpg",
  "createdAt": "...", "updatedAt": "...", "deletedAt": null,
  "tags": [{ "id": "...", "name": "travel" }],
  "boards": [{ "id": "...", "name": "Summer" }],
  "image": { "id": "...", "path": "images/<imageId>.jpg", "mimeType": "image/jpeg",
             "width": 1920, "height": 1080, "animated": false, "byteSize": 482913,
             "sha256": "...", "createdAt": "..." }
}
```

`boards` lists memberships **regardless of the board's state**; whether a board is recycled is read
from `boards.jsonl`, which carries its `deletedAt`. `image` is `null` when the pin has no image **or**
when its bytes could not be written.

### `boards.jsonl` / `tags.jsonl` / `user.json`

Boards (active **and** recycled): `id`, `name`, `description`, `createdAt`, `updatedAt`, `deletedAt`.
Tags: `id`, `name`, `createdAt`. User: `id`, `name`, `createdAt`.

### `images/`

`images/<imageId>.<ext>`, the extension from a fixed MIME map (`image/jpeg` → `jpg`, `png`, `webp`,
`gif`, `avif`, anything else → `bin`). Every entry name derives from a UUID, so no user-controlled
string ever reaches a ZIP entry path (verified: `Image.mimeType` comes from a server-side enum, not
from the client).

### `README.md`

Generated: what the archive contains, the meaning of each file, the JSONL convention, how to verify
the SHA-256 digests, and the explicit list of what is not included and why.

## 5. Domain and ports

```kotlin
data class UserDataExport(
    override val id: UUID,
    val userId: UUID,
    val state: UserDataExportState,
    val formatVersion: Int,
    val requestedAt: Instant,
    val taskId: UUID? = null,
    val completedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val storageKey: String? = null,
    val byteSize: Long? = null,
    val sha256: String? = null,
    val mediaType: String? = null,
    val fileExtension: String? = null,
    val failureCode: String? = null,
) : Identifiable
```

`UserDataExportState`: `PENDING`, `READY`, `FAILED`, `EXPIRED`, `DELETED`, `SUPERSEDED`, with
`isGone` covering the three destroyed states. `taskId` is captured from `EnqueueTask.enqueue`'s
return value so a pending export can be cancelled (`CancelTask` takes a task id).

**The nullable fields are a coverage trap, and the design answers it.** A `READY` export has all of
them, a `PENDING` one has none, but a controller dereferencing five nullables creates five branches
whose "impossible" side no test can reach, which breaks the 100% branch gate. So
`UserDataExportDownloader` returns a **non-nullable projection** built at a single validation site:

```kotlin
data class OpenedExport(
    val exportId: UUID,
    val mediaType: String,
    val fileExtension: String,
    val totalByteSize: Long,
    val sha256: String,
    val completedAt: Instant,
    val stream: InputStream,
)
```

Any null there throws `ExportNotReadyError` from **one** reachable branch, unit-testable with a
`READY` row missing its `storageKey`. The controller only ever handles non-nullable values.

### `ExportArchiveStore` port

```kotlin
data class ArchiveFormat(val mediaType: String, val fileExtension: String)
data class ArchiveEntryDigest(val path: String, val byteSize: Long, val sha256: String)

interface ArchiveSink {
    fun putTextEntry(name: String, text: String): ArchiveEntryDigest
    fun putJsonEntry(name: String, value: Any): ArchiveEntryDigest
    fun putJsonLinesEntry(name: String, values: Sequence<Any>): ArchiveEntryDigest
    fun putBinaryEntry(name: String, bytes: InputStream): ArchiveEntryDigest
}

interface ExportArchiveStore {
    val format: ArchiveFormat
    fun hasFreeSpace(requiredBytes: Long): Boolean
    fun stage(block: (ArchiveSink) -> Unit): StagedFile
    fun promote(staged: StagedFile, storageKey: String)
    fun openStream(storageKey: String, skipBytes: Long = 0): InputStream
    fun delete(storageKey: String)
    fun discard(staged: StagedFile)
    fun discardOrphanedStagedFiles(olderThan: Instant): Int
}
```

The shape mirrors `ImageStore`: stage into a temp file, promote by atomic rename, so a truncated
archive is never reachable. `stage` must **fsync** the temp file before returning (as
`FilesystemImageStore.writeAndDigest` already does, "so a promote never observes a partially-flushed
file") and must close the underlying file stream in a `finally`, since the per-entry counting stream
deliberately does not close its delegate. Orphan sweeping only considers files whose name carries the
export prefix, so it can never eat an image store's in-flight temp file even if an operator points
both stores at the same directory.

`StagedFile` moves from `domain.images` to a neutral `domain.storage` package, now that images and
exports share it.

### Repository port

`save`, `findById`, `findAllForUser(userId, cursor, pageSize)`, `findPendingForUser`,
`findReadyForUser`, `findLastRequestedAtForUser`, `findExpiredReadyExports(now)`,
`findAllExportIdsForUser`, `deleteAllForUser`.

**`findLastRequestedAtForUser` covers every state, `DELETED` and `FAILED` included.** Without that
contract, request-cancel-request is a free loop that saturates the worker and the disk while
technically respecting the one-pending-export rule.

### Domain entities gain timestamps

`User.createdAt`; `Pin.createdAt`/`updatedAt`; `Board.createdAt`/`updatedAt`; `Tag.createdAt`. All
nullable with a `null` default, meaning "not read from persistence"; the columns already exist
(`BaseModel`), so no migration is needed for them.

## 6. Use cases

- **`UserDataExportRequester.request(user, factor)`** — verifies the step-up factor through
  `Reauthenticator` **first**, then in one transaction: reject if a `PENDING` export exists
  (`EXPORT_ALREADY_IN_PROGRESS`), reject if the last request (any state) is within the minimum
  interval (`EXPORT_TOO_SOON`), mark any `READY` export `SUPERSEDED`, insert the `PENDING` row,
  enqueue `account.export`, store the returned task id. **The superseded archive's bytes are deleted
  after the commit, never inside the transaction**: deleting inside means a later rollback leaves a
  `READY` row pointing at a file that no longer exists, which serves a 500 instead of a clean error.
- **`UserDataExportBuilder.build(exportId, isLastAttempt)`** — the worker path (§8).
- **`UserDataExportGetter`** — by id (owner-checked) and the user's paginated history.
- **`UserDataExportDownloader.open(user, exportId, skipBytes)`** — owner check, state check, rejects a
  negative or out-of-range `skipBytes` itself rather than trusting its caller, returns `OpenedExport`
  with the stream **opened eagerly**, so a purge racing the download fails before the status line is
  sent rather than truncating an already-committed `200`.
- **`UserDataExportDeleter.delete(user, exportId)`** — owner check; `PENDING` cancels the task and
  moves to `DELETED`; `READY` deletes the bytes and moves to `DELETED`; terminal states are a no-op.
- **`ReapExpiredUserDataExports.reap()`** — destroys expired archives' bytes, sets `EXPIRED`, and
  **also deletes the derived storage key of every terminal row**, which is the safety net for §8's
  crash window. Sweeps orphaned staged files.
- **`UserDataExportTask`** — `KIND = "account.export"`, `MAX_ATTEMPTS = 3`.

## 7. REST surface

All endpoints `@Authenticated` and owner-scoped (`403` for a non-owner, `404` for an unknown id, a
deliberate and pre-existing convention).

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/v1/me/exports` | Requires `X-Reauthentication: password <base64url>`; `202` |
| `GET` | `/api/v1/me/exports` | Paginated history (cursor, like every other collection) |
| `GET` | `/api/v1/me/exports/{id}` | State and metadata |
| `GET` | `/api/v1/me/exports/{id}/download` | `200` or `206` |
| `DELETE` | `/api/v1/me/exports/{id}` | `204` |

Response DTO: `id`, `state`, `requestedAt`, `completedAt`, `expiresAt`, `byteSize`, `mediaType`,
`sha256`, `failureCode`, `formatVersion`. Size and media type are in the representation, not only in
the download headers, so a client can announce "3.2 GB ZIP archive" before the user commits.

### Download response

- `Content-Type` and `Content-Length` from the **projection** (§5), `ETag: "<sha256>"`,
  `Accept-Ranges: bytes`.
- `Content-Disposition: attachment` with an ASCII-sanitized `filename` **and** an RFC 6266
  `filename*=UTF-8''<percent-encoded>`. This is a security requirement: usernames are only `trim()`ed
  at registration, so treat them as hostile (quotes, CRLF, `../`, RTL overrides, arbitrary length).
  Percent-encode per RFC 5987 by hand, **not** with `URLEncoder`, which emits `+` for space and
  leaves `*` and `'` unescaped. Cap the name length (100 characters) so a huge username cannot
  produce a header some proxies reject.
- **Range requests are implemented by hand.** Quarkus REST accepts `Path`/`PathPart`/`FilePart`
  return types but documents no automatic `Range` handling, and those types would need a filesystem
  path in the controller, defeating the port. The endpoint parses `Range` and returns a
  `RestResponse<StreamingOutput>`, the pattern `ImageController.serveOriginal` already uses. Single
  `bytes=start-` and `bytes=start-end` honoured (`206` + `Content-Range`; `416` +
  `Content-Range: bytes */<total>` when unsatisfiable); multi-range and suffix ranges (`bytes=-500`)
  are answered with the full body, which is legal, and each of those choices gets a test that pins
  it. The copy is **bounded** to the slice length; seeking uses a positioned channel, never
  `InputStream.skip`.

## 8. The `account.export` task

`UserDataExportTaskHandler` delegates to the builder, passing
`isLastAttempt = context.attempt >= context.maxAttempts` (the same comparison `TaskProcessor.settle`
uses to mark a task `DEAD`).

1. Load the export; return if absent or not `PENDING`.
2. Load the user; if gone or tombstoned, mark `FAILED` and throw `PermanentTaskException` (no retry).
3. Check free space; if short, mark `FAILED` with `DISK_FULL` and throw `PermanentTaskException`.
   Filling the volume would break SQLite writes for the whole instance.
4. **Write the storage key on the row before staging**, so the bytes are always referenced.
5. `stage { sink -> ... }` writing: `README.md`, `user.json`, `boards.jsonl`, `tags.jsonl`, the image
   entries (first pin walk, collecting the paths written), `pins.jsonl` (second pin walk, referencing
   only those paths), and `manifest.json` last. The task's **lease is renewed** as entries are
   written (§15).
6. `promote(staged, storageKey)`.
7. In one transaction, **re-read the row and compare-and-set**: only if it is still `PENDING`, write
   `READY` with `completedAt`, `expiresAt`, `byteSize`, `sha256`, `mediaType`, `fileExtension`.
   Otherwise (cancelled, or the account was deleted mid-build) delete the promoted bytes and return
   without writing. A blind `save` here would resurrect a `DELETED` export the user was told was
   destroyed, or re-insert a row for a hard-deleted account (foreign keys would not stop it: FK
   enforcement is off on this datasource).
8. On failure: `discard(staged)`, mark `FAILED` if this was the last attempt, rethrow.

`TaskProcessor` swallows handler exceptions without logging, so a `DEAD` task is invisible to
operators: the export row's `FAILED` state and `failureCode` are the user-visible truth.

## 9. Retention, quotas and purge

Configuration under the `exports.` prefix in snake case, matching `images.*` and `tasks.*` (there is
no `api.` prefix in this codebase), declared in `api-worker-quarkus` next to `TaskQueueConfig` and
listed in `application.properties`:

| Key | Default | Meaning |
|---|---|---|
| `exports.data_dir` | `/var/lib/pinry/exports` | Archive root (a **new volume**, to document in deploy) |
| `exports.retention` | `P7D` | Lifetime of a ready archive |
| `exports.minimum_interval` | `PT1H` | Minimum delay between two requests, all states counted |
| `exports.purge_interval` | `PT1H` | How often the purge runs |
| `exports.staged_file_max_age` | `PT6H` | Age past which a staged temp file is orphaned |
| `exports.page_size` | `500` | Pin pagination page size |

**At most one live archive per user.** A second `PENDING` request is refused, enforced in the use
case **and** by a partial unique index (`unique(user_id) where state = 'PENDING'`) for the
double-click race. The constraint violation surfaces as an Ebean exception, which the **persistence
adapter** translates into `ExportAlreadyInProgressError`; translating it in the use case is
impossible, since Konsist bans `io.ebean` imports there. Untranslated, it would be a 500 where the
spec promises 409.

**Purge** runs on its **own** single-thread scheduler, not the task worker's: that thread already
carries the poll loop and the lease reaper, and deleting several multi-gigabyte archives would block
task claiming for its whole duration.

## 10. Account deletion erases exports

Without this, deleting an account leaves a complete copy of its data on disk for up to seven days.
`AccountDeletionCleaner` deletes the export rows inside its transaction (before the user row) and the
bytes after the commit, alongside the image bytes. It deletes the **key derived from each export id**
rather than the `storage_key` column, so an archive promoted by a builder that died before writing
its row is still reclaimed.

## 11. Errors

| Code | Status | When |
|---|---|---|
| `EXPORT_ALREADY_IN_PROGRESS` | `409` | A `PENDING` export already exists |
| `EXPORT_TOO_SOON` | `429` | Within the minimum interval; sets `Retry-After` (at least 1) |
| `EXPORT_DOES_NOT_EXIST` | `404` | Unknown id (named like `PIN_DOES_NOT_EXIST`) |
| `EXPORT_INSUFFICIENT_PERMISSIONS` | `403` | Not the owner |
| `EXPORT_NOT_READY` | `409` | Download while `PENDING` or `FAILED`, or a `READY` row missing fields |
| `EXPORT_GONE` | `410` | Download while `EXPIRED`, `DELETED` or `SUPERSEDED` |

Reused: `REAUTHENTICATION_FAILED` (403), `UNSUPPORTED_REAUTHENTICATION_FACTOR` (400). Each new code
is an arm of `BaseErrorMapper`'s exhaustive `when` **and** must land in the same commit as the enum
values, since the `when` has no `else` and would otherwise fail to compile. Verified: jakarta.ws.rs
4.0.0 has `TOO_MANY_REQUESTS`, `GONE` and `REQUESTED_RANGE_NOT_SATISFIABLE` constants, so no new
title fallback is needed.

## 12. Persistence and migration

Migration `1.10` creates `user_data_exports`: `id`, `user_id` (FK), `state`, `format_version`,
`task_id`, `requested_at`, `completed_at`, `expires_at`, `storage_key`, `byte_size`, `sha256`,
`media_type`, `file_extension`, `failure_code`, plus `when_created` / `when_modified`. Indexes on
`(user_id, state)` and `(expires_at)`; the partial unique index of §9 is hand-written in its own
migration file so a regeneration cannot silently drop it.

The state is stored as a **plain `String` column converted in the mapper**, following `TaskModel` and
`ImageDownloadModel`: domain enums are deliberately kept out of the Ebean entities. No blob ever goes
into the database.

## 13. Testing strategy

TDD, 100% branch coverage per package. Beyond the mechanical unit tests, these carry the real risk:

1. **Open the produced archive and inspect it, with a real worker.** Seed pins (one recycled), a
   recycled board with a pin in it, tags and a real image; assert manifest counts and digests, one
   JSONL line per pin, the recycled pin present with its `deletedAt`, **the recycled board present
   and still linked from its pin**, and image bytes byte-identical to what was uploaded. This test is
   written **early**, not last: mocked repositories return shapes the real persistence never produces,
   which is exactly how the account-deletion NPE survived every unit test and every code review.
2. **Wrong step-up factor**: the request is refused and no task is enqueued. The "missing header"
   case is not enough; a wrong password must fail too.
3. **Account deletion with a ready export**: no row, no file on disk.
4. **Purge**: bytes gone, state `EXPIRED`, row present.
5. **Compare-and-set**: an export cancelled mid-build ends `DELETED` with no bytes, never `READY`.
6. **ZIP64**, at the adapter level, driving the sink with more than 65535 tiny entries.
7. **Headers come from the row**: persist an export whose `mediaType`/`fileExtension` differ from the
   adapter's current format and assert the response carries the stored values.
8. **Hostile username** through `Content-Disposition` (quotes, CRLF, `../`, non-ASCII, over-long).
9. **Golden JSON** for every `Exported*` type, pinning the published format.

## 14. Risks and accepted trade-offs

- **N+1 on a very large account** (§3). Accepted; a bulk read path is the future optimisation.
- **No progress reporting.** A user watching a multi-gigabyte export sees only `PENDING`.
- **Per-user disk is bounded, aggregate disk is bounded only by the free-space check.** A per-account
  storage quota is a separate, broader concern.
- **Export while the account is being deleted.** The compare-and-set (§8) makes the outcome safe; the
  builder may still hit the known tombstoned-author NPE while walking pins, which now merely fails
  the task. Note that promoting timestamps into `UserModelMapper` turns that NPE into an
  `UninitializedPropertyAccessException` (`whenCreated` is `lateinit`): same race, different symptom,
  to record in the handoff.
- **Two walks see slightly different states.** Images first (§3) removes dangling references; a pin
  created between the walks can still make `counts.pins` and the line count diverge by a few.

## 15. Two pre-existing defects this feature must fix first

Both are latent today and only become harmful when an export runs. Each gets its own commit, ahead of
the feature.

- **Cursor pagination has no tie-breaker.** `PinModelSortStrategy` filters and orders on
  `whenCreated` alone, and `ModelPaginationHelper` only drops the pivot. If more than one page of
  pins shares a timestamp (bulk import, tight test seeding), the cursor never advances: the existing
  API just serves a stuck page, but the export drains the cursor to exhaustion and would write an
  unbounded ZIP until the disk fills. Fix: `(whenCreated, id)` as the keyset, same for
  `DELETED_AT_DESC`.
- **The task queue re-claims a task whose handler never returns.** `tasks.lease_duration` is one
  minute and the reaper runs every 30 seconds, so any export exceeding a minute is picked up by a
  second worker while the first still runs, forever: `claimNext` never checks `attempts` against
  `maxAttempts`, and a handler that never returns never reaches `settle`, so the task never dies.
  Today the only long task (`pin.download`) is hand-bounded under the lease, which is why nobody hit
  this. Fix, in three parts: a **`renewLease(id, leaseId, until)` queue operation** called by the
  builder as it writes entries; **`claimNext` refusing tasks that have exhausted their attempts**,
  marking them `DEAD`; and the **compare-and-set** of §8, which makes a duplicated build harmless
  rather than corrupting.

## 16. References

- GDPR Article 20: <https://gdpr-info.eu/art-20-gdpr/>
- GitHub personal data archive (7-day retention): <https://docs.github.com/en/get-started/archiving-your-github-personal-account-and-public-repositories/requesting-an-archive-of-your-personal-accounts-data>
- Mastodon tar.gz to ZIP, and >4 GB reports: <https://github.com/tootsuite/mastodon/issues/9318>
- Data Transfer Initiative principles: <https://dtinit.org/assets/dtp-overview.pdf>
- Google Data Portability API (job states, cancel/retry): <https://developers.google.com/data-portability/reference/rest>
- ZIP64 limits and `STORED` requirements: <https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/archivers/zip/ZipArchiveOutputStream.html>
