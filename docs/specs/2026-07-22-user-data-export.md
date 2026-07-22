# User data export (portability)

Date: 2026-07-22
Status: approved design, pending implementation plan
Depends on: the task queue (`EnqueueTask` + a new `TaskHandler`), the step-up re-authentication brick
(`Reauthenticator`, `X-Reauthentication`), `ImageStore`, the pin / board / tag / image repositories and
their cursor pagination, `TransactionRunner`, `Clock`. One new external dependency in an adapter module
(`jackson-databind`, already in the version catalog).

## 1. Goal

Let an authenticated user download **all** of their data as a single, self-contained archive, so they
stay in control of it and are never held hostage by this instance. This is the "User data export /
import (portability)" P1 item in `docs/backlog.md`, reduced here to its export half.

The archive is **self-contained**: image bytes travel inside it. An export that only lists metadata and
leaves the pixels behind an authenticated API would be worthless the day the user loses their account
on this instance, which is precisely the day portability matters.

The archive format is designed as the **input contract of the future importer**, even though no
importer ships here.

## 2. Scope

**In scope:**

- **Request an export** (`POST /api/v1/me/exports`), behind step-up re-authentication, returning `202`
  and an export resource.
- **Asynchronous archive building** in the worker (`account.export` task): a ZIP holding a manifest, a
  human-readable README, the profile, JSONL collections (pins, boards, tags) and the original image
  bytes.
- **Track and download**: `GET /api/v1/me/exports` (history), `GET /api/v1/me/exports/{id}` (state),
  `GET /api/v1/me/exports/{id}/download` (bytes, authenticated, owner-scoped).
- **User-initiated destruction** (`DELETE /api/v1/me/exports/{id}`): cancels a pending export or
  destroys a ready one immediately.
- **Retention and quotas**: 7-day retention, at most one active export per user, a minimum delay
  between two exports.
- **Automatic purge** of expired archives, shipped in v1, not deferred to a backlog item.
- **Account deletion erases exports** (rows and bytes).
- **Creation timestamps promoted into the domain** for `User`, `Pin`, `Board` and `Tag`: they already
  exist in the database via `BaseModel` (`whenCreated` / `whenModified`) but are absent from the domain
  entities, so an export could not carry them.

**Out of scope (deferred):**

- **Import.** Stays a backlog item. This spec only commits to a versioned format that an importer can
  read.
- **Encryption of the archive.** Rejected in §3.
- **Email delivery / signed download links.** There is no email address in the data model, and a
  secret-bearing URL is a downgrade from an authenticated download (§3).
- **Scheduled or recurring exports.** No use case yet.
- **Exporting derived renditions.** Regenerable, and they would multiply the archive size.
- **Selective/partial export** (a single board, a date range). One button, everything, no options.

## 3. Key decisions (rationale captured for the plan)

- **Asynchronous, on the existing task queue, not a synchronous stream.** A synchronous
  `GET /me/export` needs no table, no retention and no purge, but it degrades worst exactly where it
  matters most: on a large account it holds an HTTP thread for minutes, dies on any reverse-proxy
  timeout or network blip, and offers **no resumption** (start again from zero), no progress, no
  announced size and no verifiable digest. Asynchronous building matches every reference
  implementation (Google Takeout, GitHub user migrations, Mastodon, Discourse) and reuses machinery
  this codebase already has: task queue, dedicated worker module, a `202` precedent on
  `DELETE /api/v1/me`.
- **Password hashes and session tokens are never exported.** They are secrets, not user-useful data. A
  hash in an archive turns a stolen archive into an offline password-cracking target, for zero benefit:
  the user cannot do anything with their own bcrypt hash. Sessions are secrets *and* expired by the
  time they would be read.
- **Recycle-bin content is exported.** Soft-deleted pins and boards are still restorable by their
  owner, so they are still the user's data. Silently dropping them from an archive advertised as
  exhaustive would be a lie of omission. They carry their `deletedAt` so a reader can tell them apart.
- **Creation timestamps are promoted into the domain.** Without them the archive loses the chronology
  of the collection, and a future import would re-date everything to the day of the import. The
  columns already exist; only the entities, mappers and repository reads need to carry them. Doing it
  now is far cheaper than migrating an already-published format version later.
- **ZIP, not tar.gz.** A ZIP opens with a double-click on the three major desktop platforms with
  nothing installed, and allows random access to a single entry without inflating the whole archive.
  Mastodon moved from tar.gz to ZIP in 4.2 for the same reasons.
- **Image entries are written with compression level 0, not `STORED`.** Images are already compressed
  (JPEG/PNG/WebP), so deflating them burns CPU for roughly nothing. The obvious answer, `STORED`, is a
  trap: a `STORED` entry requires the CRC32 **and** the size *before* the entry is written, which would
  force reading every image **twice**. A `DEFLATED` entry at level 0 needs neither (the data descriptor
  carries them afterwards), streams in a single pass, and costs a few bytes per block. JSON entries
  keep normal compression.
- **ZIP64 is mandatory, and must be verified rather than assumed.** A classic ZIP caps at 4 GB and
  65535 entries; a few thousand pins reach the entry cap long before the size cap. Mastodon has
  open reports of archives above 4 GB misbehaving. `java.util.zip.ZipOutputStream` emits ZIP64
  extensions when needed, but "when needed" is exactly the branch nobody exercises, so it gets an
  explicit test (§13).
- **JSONL for collections, JSON for singletons.** One entity per line lets the worker write in
  constant memory whatever the collection size, and lets a future importer read the same way. A
  monolithic `pins.json` forces both sides to hold everything in memory, and a truncated file becomes
  entirely unparseable instead of losing only its last line. The manifest and the profile are single
  objects and stay plain JSON, the manifest being meant to be read by a human too.
- **Tags and boards are denormalized into each pin (id *and* name).** A reader should not have to join
  three files by hand to know what a pin is tagged with. The duplication is negligible once deflated,
  and the ids keep the archive unambiguous for an importer.
- **A `formatVersion` ships in v1.** This is what makes the format evolvable: a future importer knows
  what it is reading, and today's archives stay readable when v2 exists.
- **Per-entry SHA-256 in the manifest, plus a whole-archive SHA-256 exposed by the API.** For an
  artifact users are expected to keep for years, being able to prove it is not corrupted is the
  minimum. The whole-archive digest comes for free from `StagedFile`.
- **The manifest is the last entry written.** It contains the digests of the other entries, so it can
  only be produced once they exist. ZIP entry order is irrelevant to readers (the central directory is
  the index), so this costs nothing.
- **The archive documents its own exclusions.** The README and the manifest list what is deliberately
  *not* included and why (password hashes, sessions, renditions). This is the Data Transfer
  Initiative's transparency principle: without it, a user cannot distinguish a deliberate omission from
  a silent truncation.
- **No archive encryption.** It requires key or passphrase management that, done poorly, gives false
  assurance, and an archive whose key is lost is lost data. Confidentiality is enforced by access
  control on the download, which the user already has credentials for.
- **Authenticated download, no secret-bearing link.** GitHub and Google mail a signed URL because they
  have an email channel; this instance has none, and a URL carrying a secret leaks through browser
  history, proxy logs, clipboards and referrers. The cost is ergonomic (an `<a download>` cannot carry
  an `Authorization` header, so the web client fetches then hands the blob to the user), not
  architectural. A short-lived one-time download token remains a possible later addition if a client
  really needs it.
- **Step-up re-authentication to *request* an export.** A stolen session token can already read the
  data pin by pin; an export turns that grind into one file containing everything, images included.
  That is the difference between a theoretical leak and a one-click exfiltration. The
  `Reauthenticator` brick and the `X-Reauthentication` header already exist, so the marginal cost is
  one controller parameter, and TOTP step-up will apply to exports for free when it lands.
- **The ZIP format stays an implementation detail of the adapter.** The domain port speaks in archive
  entries (`putJsonEntry`, `putJsonLinesEntry`, `putBinaryEntry`, `putTextEntry`); ZIP mechanics and
  Jackson serialization both live in `api-storage-filesystem`. `api-usecases` therefore gains **no**
  serialization dependency and the use case describes *what* goes into the archive, never *how* it is
  encoded. Switching to another container later is an adapter change.
- **…which is precisely why the effective media type and size must travel through the layers.** If the
  adapter owns the container format, the controller cannot hardcode `application/zip`: it would be
  asserting something only the adapter knows, and it would start lying the day the adapter changes.
  So the port **declares** its format (`ArchiveFormat(mediaType, fileExtension)`), the builder
  **persists** that media type and extension on the export row, and the controller **reads them back**
  from the row. An archive built as a ZIP is therefore still served as `application/zip` with a `.zip`
  name years later, even if the adapter has moved on: the row records what was actually produced, not
  what the current code would produce. The same rule governs the size: `byteSize` is measured at build
  time, stored, exposed in the API representation, and sent as `Content-Length` so the browser shows a
  real progress bar instead of an open-ended spinner. This mirrors `ImageController.serveOriginal`,
  which already serves `Content-Type` and `Content-Length` from the stored `Image`, never from a
  constant.
- **Enumeration reuses the existing cursor pagination.** `findAllPinsForUser` returns a full `List<Pin>`
  and excludes the recycle bin, so it is unusable here: 50 000 pins would land in memory at once. Two
  paginated passes (`findPinsForUser`, then `findSoftDeletedPinsForUser`) give constant memory with no
  new repository method and no N+1.
- **`EXPIRED` and `DELETED` are distinct states.** "The system purged it" and "I destroyed it" are not
  the same fact, and the export history is shown to the user.
- **Rows are kept after purge; only the bytes are destroyed.** A retained row costs a few dozen bytes,
  avoids writing a second sweep to garbage-collect rows, lets the API answer "this export expired"
  instead of an ambiguous `404`, and gives the user an audit trail of their own exports.

## 4. Archive format, version 1

File name: `<ISO date>-pinry-export-<username>.zip`, e.g. `2026-07-22-pinry-export-alice.zip`. The date
leads so archives sort chronologically in a download folder. Date only, not a full timestamp: `:` is
illegal in Windows file names, and browsers already de-duplicate same-name downloads.

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

All timestamps are ISO-8601 UTC (`2026-07-22T10:15:30Z`). All ids are UUID strings. Absent values are
`null`, never omitted, so a reader never has to guess between "absent" and "unset".

The adapter's Jackson instance is **configured explicitly and locally** for this: ISO-8601 instants,
never epoch numbers (`WRITE_DATES_AS_TIMESTAMPS` disabled, `JavaTimeModule` registered), and no
inherited configuration from the REST layer's mapper. The archive format is a published contract and
must not silently change the day someone tunes the API's serialization.

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
  "entries": [
    { "path": "user.json", "byteSize": 118, "sha256": "..." },
    { "path": "pins.jsonl", "byteSize": 918273, "sha256": "..." }
  ],
  "excluded": [
    { "what": "password hashes", "why": "secrets; useless to you, dangerous if this archive leaks" },
    { "what": "session tokens", "why": "secrets; expired and meaningless outside this instance" },
    { "what": "image renditions", "why": "derived from the original bytes, regenerable" }
  ]
}
```

`entries` covers every entry except the manifest itself. `counts` lets a reader detect truncation
without parsing every line.

### `user.json`

```json
{ "id": "3fa85f64-...", "name": "alice", "createdAt": "2026-01-04T09:00:00Z" }
```

### `pins.jsonl` (one object per line)

```json
{
  "id": "...", "description": "...",
  "sourceContextUrl": "https://example.org/article",
  "sourceMediaUrl": "https://example.org/image.jpg",
  "createdAt": "...", "updatedAt": "...", "deletedAt": null,
  "tags": [{ "id": "...", "name": "travel" }],
  "boards": [{ "id": "...", "name": "Summer" }],
  "image": {
    "id": "...", "path": "images/<imageId>.jpg", "mimeType": "image/jpeg",
    "width": 1920, "height": 1080, "animated": false, "byteSize": 482913,
    "sha256": "...", "createdAt": "..."
  }
}
```

`deletedAt` non-null means the pin was in the recycle bin. `image` is `null` when the pin has no image
(never downloaded, or the download failed). `image.path` is explicit: a reader never reconstructs a
path from a convention.

### `boards.jsonl` / `tags.jsonl`

Boards: `id`, `name`, `description`, `createdAt`, `updatedAt`, `deletedAt`. Tags: `id`, `name`,
`createdAt`.

### `images/`

One file per image, named `<imageId>.<ext>`, the extension derived from the stored MIME type
(`image/jpeg` → `jpg`, `png`, `webp`, `gif`, `avif`; anything unknown → `bin`). Each image belongs to
exactly one pin, so there is nothing to de-duplicate. Every entry name is derived from a UUID, so no
user-controlled string ever reaches a ZIP entry path.

### `README.md`

Generated, human-readable: what the archive contains, the meaning of each file, the JSONL convention,
how to verify the SHA-256 digests, and the explicit list of what is *not* included and why.

## 5. Domain & ports

### New entity

```kotlin
data class UserDataExport(
    override val id: UUID,
    val userId: UUID,
    val state: UserDataExportState,
    val formatVersion: Int,
    val requestedAt: Instant,
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

`mediaType` and `fileExtension` are copied from the port's declared `ArchiveFormat` when the archive is
promoted, and are the **only** source the download endpoint uses. They are stored rather than derived
so an archive keeps being served as what it actually is, whatever the adapter produces later.

`UserDataExportState`: `PENDING`, `READY`, `FAILED`, `EXPIRED`, `DELETED`, `SUPERSEDED`.

State transitions, all of them one-way:

| From | To | Trigger |
|---|---|---|
| (none) | `PENDING` | `POST /me/exports` |
| `PENDING` | `READY` | worker finished and promoted the archive |
| `PENDING` | `FAILED` | worker exhausted its attempts |
| `PENDING` | `DELETED` | user cancelled |
| `READY` | `EXPIRED` | purge, past `expiresAt` |
| `READY` | `DELETED` | user destroyed it |
| `READY` | `SUPERSEDED` | user requested a new export, which replaces this one |

The four terminal states carry distinct facts and are deliberately not collapsed: "the system purged
it", "I destroyed it", "I replaced it" and "it failed" are different answers to the same user
question. They behave identically at download time (`410`), so the extra values cost branches only
where the distinction is actually written, not everywhere it is read.

### `StagedFile` moves to `domain.storage`

`StagedFile` (`path`, `byteSize`, `contentHash`) currently lives in `domain.images` but becomes shared
with exports. It moves to a neutral `domain.storage` package (`git mv` plus import updates in
`ImageStore`, `RenditionCache` and their adapters and tests). No behaviour change.

### `ExportArchiveStore` port

```kotlin
/** What the adapter actually produces, surfaced so no upper layer has to assume it. */
data class ArchiveFormat(val mediaType: String, val fileExtension: String)

interface ExportArchiveStore {
    val format: ArchiveFormat

    /** Writes a new archive into a temp file, measuring size and SHA-256 in one pass. */
    fun stage(block: (ArchiveSink) -> Unit): StagedFile
    fun promote(staged: StagedFile, storageKey: String)
    fun openStream(storageKey: String, skipBytes: Long = 0): InputStream
    fun delete(storageKey: String)
    fun discard(staged: StagedFile)
    /** Deletes staged temp files left behind by a crash, older than [olderThan]. */
    fun discardOrphanedStagedFiles(olderThan: Instant): Int
}

interface ArchiveSink {
    fun putTextEntry(name: String, text: String): ArchiveEntryDigest
    fun putJsonEntry(name: String, value: Any): ArchiveEntryDigest
    fun putJsonLinesEntry(name: String, values: Sequence<Any>): ArchiveEntryDigest
    fun putBinaryEntry(name: String, bytes: InputStream): ArchiveEntryDigest
}

data class ArchiveEntryDigest(val path: String, val byteSize: Long, val sha256: String)
```

The shape mirrors `ImageStore` deliberately: stage into a temp file, then promote by atomic rename.
This is what makes a truncated archive unreachable by the API: the row only becomes `READY` after the
rename succeeded. Each `put*` returns its digest so the use case can assemble the manifest without the
sink knowing anything about manifests.

`putJsonLinesEntry` takes a `Sequence` so pages are pulled lazily: the use case never materializes a
collection.

### Repository port

`UserDataExportRepositoryInterface`: `save`, `findById`, `findAllForUser`, `findActiveForUser`
(`PENDING` or `READY`), `findLastRequestedAtForUser`, `findExpiredReadyExports(now)`,
`deleteAllForUser`.

### Domain entities gain timestamps

`User` gains `createdAt`; `Pin`, `Board` and `Tag` gain `createdAt` and `updatedAt` (tags only
`createdAt`, they have no mutable field). These come from `BaseModel.whenCreated` / `whenModified`,
already populated in the database. Mappers, repository reads and every construction site (including
tests and fixtures) are updated. No migration is needed for them: the columns exist.

## 6. Use cases (`api-usecases`)

- **`UserDataExportRequester.request(user)`** — inside one transaction: reject if a `PENDING` export
  exists (`EXPORT_ALREADY_IN_PROGRESS`), reject if the last request is more recent than the configured
  minimum interval (`EXPORT_TOO_SOON`), mark any `READY` export `SUPERSEDED` and delete its bytes,
  insert the new `PENDING` row, enqueue the `account.export` task with the export id as payload.
  Returns the export. A `READY` export blocks nothing: it is replaced, which is what keeps disk usage
  bounded to one archive per user.
- **`UserDataExportBuilder.build(exportId)`** — the worker path (§8).
- **`UserDataExportGetter`** — by id (owner-checked) and the user's list.
- **`UserDataExportDownloader.open(user, exportId)`** — owner check, state check, returns the metadata
  plus a stream (with an optional byte offset for range requests).
- **`UserDataExportDeleter.delete(user, exportId)`** — owner check; on `PENDING` cancels the task
  (`CancelTask`, which already exists) and moves to `DELETED`; on `READY` deletes the bytes and moves
  to `DELETED`; on a terminal state it is a no-op (idempotent).
- **`ReapExpiredUserDataExports.reap()`** — for each `READY` export past `expiresAt`: delete the bytes,
  set `EXPIRED`, clear `storageKey`. Also calls `discardOrphanedStagedFiles`. Returns the count.
- **`UserDataExportTask`** — `KIND = "account.export"`, `MAX_ATTEMPTS = 3` (lower than
  `account.delete`'s 5: rebuilding a multi-gigabyte archive five times is expensive, and a failure here
  is recoverable by the user asking again).

## 7. REST surface (`api-presentation-quarkus`)

All endpoints are `@Authenticated` and owner-scoped; another user's export is `403`, matching the
existing controllers.

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/v1/me/exports` | Requires `X-Reauthentication: password <base64url>`. `202` + body |
| `GET` | `/api/v1/me/exports` | The user's export history, newest first |
| `GET` | `/api/v1/me/exports/{id}` | State and metadata |
| `GET` | `/api/v1/me/exports/{id}/download` | `200` (or `206`) with the bytes |
| `DELETE` | `/api/v1/me/exports/{id}` | `204`; cancels or destroys |

Response DTO: `id`, `state`, `requestedAt`, `completedAt`, `expiresAt`, `byteSize`, `mediaType`,
`sha256`, `failureCode`, `formatVersion`. `byteSize` and `mediaType` are in the representation, not
only in the download headers, so a client can announce "3.2 GB ZIP archive" before the user commits to
the download.

### Download response

- `Content-Type` and `Content-Length` come **from the export row** (`mediaType`, `byteSize`), never
  from a constant in the controller, exactly as `ImageController.serveOriginal` serves `image.mimeType`
  and `image.byteSize`. `ETag: "<sha256>"`, `Accept-Ranges: bytes`.
- `Content-Disposition: attachment` with **both** an ASCII-sanitized `filename` and an RFC 6266
  `filename*=UTF-8''<percent-encoded>`, whose extension is the row's `fileExtension` (§5), so the name
  can never advertise a container the archive is not. **This is a security requirement, not cosmetics**: usernames
  are only trimmed and uniqueness-checked at registration (`UserCreator`), so a name can legitimately
  contain quotes, slashes, `..`, CR/LF or arbitrary Unicode. Unsanitized, that is header injection and
  a hostile file name on the client. Sanitization keeps `[A-Za-z0-9._-]`, replaces anything else with
  `-`, collapses repeats, and falls back to the user id if nothing survives.
- **Range requests are implemented by hand, deliberately.** Quarkus REST does accept `Path`,
  `PathPart`, `FilePart` and Vert.x `AsyncFile` as return types, but it documents **no automatic
  `Range` handling**, and those types would require a real filesystem path in the controller, which
  would defeat `ExportArchiveStore` (the port exposes `openStream`, never a path; where the bytes live
  is the adapter's business). So the endpoint parses `Range` itself and returns a
  `RestResponse<StreamingOutput>`, which is the pattern `ImageController.serveOriginal` already uses
  successfully with a hand-set `Content-Length`. A single `bytes=start-` or `bytes=start-end` range is
  honoured (`206` plus `Content-Range`, `Content-Length` = the slice length, `416` plus
  `Content-Range: bytes */<total>` when unsatisfiable); a multi-range request is answered with the full
  body, which is allowed. Without this, a connection dropping at 90% of a 3 GB archive means starting
  over, which would undercut the very reason the design is asynchronous.
- Two implementation traps for the plan: `InputStream.skip` may skip **fewer** bytes than asked, so
  seeking must use `skipNBytes` or a positioned `SeekableByteChannel`; and the copy must be **bounded**
  to the slice length, since `copyTo` would happily stream past `end` and contradict the announced
  `Content-Length`.

## 8. The `account.export` task (worker path)

`UserDataExportTaskHandler` in `api-worker-quarkus` delegates to `UserDataExportBuilder`, mirroring
`AccountDeletionTaskHandler`.

`UserDataExportBuilder.build(exportId)`:

1. Load the export. If it is absent or not `PENDING`, **return** (idempotent no-op): the user may have
   cancelled it, or a retry may be running after a successful attempt.
2. Load the user. If the user is gone or tombstoned, mark the export `FAILED` and return: an account
   being erased must not produce an archive of the data being erased.
3. `exportArchiveStore.stage { sink -> ... }`, writing in this order: `README.md`, `user.json`,
   `pins.jsonl` (two paginated passes: active pins, then recycle bin), `boards.jsonl`, `tags.jsonl`,
   each image as it is met while walking the pins, and finally `manifest.json` built from the
   accumulated digests and counts.
4. `promote(staged, storageKey)` where `storageKey` derives from the export id.
5. In one transaction, set `READY`, `completedAt`, `expiresAt = now + retention`, `byteSize`, `sha256`,
   `storageKey`, and `mediaType` / `fileExtension` copied from `exportArchiveStore.format`.
6. On any failure: `discard(staged)`, let the exception propagate so the queue retries. On the final
   attempt the export must end as `FAILED` rather than staying `PENDING` forever.

**Point of attention inherited from account deletion.** `TaskProcessor` swallows a throwing handler
into a retryable outcome without logging, so a task that exhausts its attempts and goes `DEAD` is
invisible to operators. An export must therefore never rely on the task state to tell the user what
happened: the `FAILED` state and `failureCode` on the export row are the user-visible truth.

**Verified: the brick already exists.** `TaskContext(attempt, maxAttempts)` is handed to every handler,
populated from `ClaimedTask.attempts` / `maxAttempts`, and `TaskProcessor.settle` marks a task `DEAD`
on exactly `attempts >= maxAttempts`. The handler therefore passes `isLastAttempt =
context.attempt >= context.maxAttempts` to the builder, using the same comparison as the processor, and
the builder writes `FAILED` before rethrowing on that last attempt. The use case never learns anything
about the queue beyond that boolean. `PermanentTaskException` is used for failures that must not be
retried at all (the user is gone or tombstoned): it marks the task dead immediately, and the builder
sets `FAILED` first.

## 9. Retention, quotas and purge

Configuration (`ExportsConfig`, mirroring `TaskQueueConfig`):

| Key | Default | Meaning |
|---|---|---|
| `api.exports.retention` | `P7D` | Lifetime of a ready archive |
| `api.exports.minimum-interval` | `PT1H` | Minimum delay between two requests |
| `api.exports.purge-interval` | `PT1H` | How often the purge runs |
| `api.exports.page-size` | `500` | Enumeration page size |

**At most one live archive per user** bounds disk usage: requesting a new export while a `READY` one
exists marks the old one `SUPERSEDED` and deletes its bytes, and a second `PENDING` export is refused
outright. The refusal is enforced in the use case inside the transaction **and** by a partial unique index
(`unique(user_id) where state = 'PENDING'`), because two concurrent requests (a double-click) would
otherwise both pass the check. SQLite supports partial indexes; the generated migration is edited by
hand to add it.

**Purge**: a dedicated `ExportRetentionLifecycle` in `api-worker-quarkus` schedules
`ReapExpiredUserDataExports.reap()` on the existing `TASK_POLL_SCHEDULER`, exactly as
`TaskWorkerLifecycle` schedules `reapExpiredTasks.reap()`, once at startup and then at
`purge-interval`. A separate lifecycle bean rather than more responsibilities inside
`TaskWorkerLifecycle`, whose job is the task worker.

## 10. Account deletion must erase exports

`AccountDeletionCleaner` currently knows nothing about this table. Without a change, a user who
exports then deletes their account leaves a **complete, intact copy of all their data** on the server
for up to seven days, while the database rows are gone. That is the exact opposite of what the delete
button promises.

The cleaner therefore collects the user's export storage keys before the transaction, deletes the rows
inside it (before the user row, like every other child table), and deletes the bytes after the commit
alongside the image bytes. This is a dedicated integration test, not a line of code trusted on sight
(§13).

## 11. Errors

| Code | Status | When |
|---|---|---|
| `EXPORT_ALREADY_IN_PROGRESS` | `409` | A `PENDING` export already exists |
| `EXPORT_TOO_SOON` | `429` | Within `minimum-interval` of the last request; sets `Retry-After` |
| `EXPORT_NOT_FOUND` | `404` | Unknown id |
| `EXPORT_NOT_READY` | `409` | Download attempted while `PENDING` or `FAILED` |
| `EXPORT_GONE` | `410` | Download attempted while `EXPIRED`, `DELETED` or `SUPERSEDED` |

Reused as-is: `REAUTHENTICATION_FAILED` (`403`), `UNSUPPORTED_REAUTHENTICATION_FACTOR` (`400`), and
`403` for a non-owner. Each new code is added to the exhaustive `BaseErrorMapper` `when` (no `else`)
with its matching `BaseErrorMapperTest` arm, since every arm is a branch under the 100% branch coverage
gate. `429` and `410` have no `jakarta.ws.rs` `Response.Status` constant, so their tests assert the raw
status code plus the `ProblemDetail` fields, mirroring the existing `IMAGE_INVALID` case.

## 12. Persistence & migration

Migration `1.10` creates `user_data_exports`: `id` (PK), `user_id` (FK, restrict), `state`,
`format_version`, `requested_at`, `completed_at`, `expires_at`, `storage_key`, `byte_size`, `sha256`,
`media_type`, `file_extension`, `failure_code`, plus `when_created` / `when_modified` from
`BaseModel`. Indexes on `(user_id, state)` and `(expires_at)`, plus the hand-added partial unique
index of §9.

Archives live under the data directory next to images, via `DataDirPaths`, in their own subtree. **No
blob ever goes into the database**: the row carries metadata and a storage key only.

## 13. Testing strategy

TDD, integration first, then use cases, then repositories, with 100% branch coverage per package.
Beyond the mechanical unit tests, four tests carry the real risk. The account-deletion sub-project is
the precedent: a bug that every mocked unit test and every code-read review missed was caught only by
a real end-to-end test.

1. **Open the produced archive and inspect it, with a real worker.** Seed a user with pins (including
   one in the recycle bin), boards, tags and a real image; request an export; wait for `READY`;
   download it; open the ZIP; assert the manifest counts and digests, one JSONL line per pin, the
   recycle-bin pin present with its `deletedAt`, and **the image bytes byte-identical to what was
   stored**. Nothing less proves the archive is usable.
2. **Account deletion with a ready export**: no row, and **no file left on disk**. The confidentiality
   hole of §10 gets its own test.
3. **Purge**: a `READY` export past `expiresAt` ends `EXPIRED`, its bytes are gone, its row remains,
   and a fresh export is untouched.
4. **ZIP64**: an archive with more than 65535 entries is produced and re-opened successfully. Written
   **at the adapter level**, driving `ArchiveSink` directly with many tiny entries, never by seeding
   65535 pins in the database. This is the branch that silently breaks large accounts and that no
   ordinary test reaches.

Also covered: the concurrent double request (one `202`, one `409`), the cooldown (`429` with
`Retry-After`), download of a non-ready and of a gone export, a non-owner (`403`), a missing or
malformed step-up header, cancellation of a `PENDING` export, a range request (`206` with the right
bytes, `416` when unsatisfiable), and **`Content-Disposition` sanitization with a hostile username**
(quotes, CRLF, `../`, non-ASCII).

One more that is cheap and guards the layering: **the download headers come from the row**. Persist an
export whose `mediaType` / `fileExtension` differ from the current adapter's format and assert the
response carries the stored values, not the adapter's. This is the test that fails the day someone
"simplifies" the controller by hardcoding `application/zip`.

## 14. Risks / open points

- **The archive is built in one pass with no progress reporting.** A user watching a multi-gigabyte
  export sees only `PENDING`. Acceptable for v1; a percentage would require the worker to report
  progress, which the queue does not model.
- **No cap on archive size.** An archive is bounded by the user's own image bytes, which are already
  capped per image at upload. A per-account storage quota is a separate, broader concern.
- **Export while images are being written.** A pin whose download completes mid-export may appear
  without its image. The archive stays internally consistent (the pin's `image` is `null`), and the
  user can export again. Locking the account during an export would be worse.
- **Account deleted while an export is building.** The builder checks the user at step 2, but the
  account can be tombstoned right after. From then on, `findPinsForUser` maps `PinModel.author` onto a
  soft-deleted `UserModel`, and that is the exact NPE that broke account deletion (see the
  profile-management handoff): Ebean's soft-delete predicate returns a partial row with a null `name`
  and the non-null Kotlin constructor throws. Here the consequence is bounded (the task retries, then
  the export ends `FAILED`, and `AccountDeletionCleaner` removes its rows and bytes anyway), so the
  race is **accepted, not fixed**. It is written down because the failure mode looks mysterious in a
  log and has already cost this codebase one silent bug.
- **Deleted-account residue.** If `account.delete` fails partially, export rows and bytes are part of
  the residue the P2 GC item must eventually reclaim.

## 15. References

- GDPR Article 20 (structured, commonly used, machine-readable format): <https://gdpr-info.eu/art-20-gdpr/>
- GitHub personal data archive (7-day retention and link expiry):
  <https://docs.github.com/en/get-started/archiving-your-github-personal-account-and-public-repositories/requesting-an-archive-of-your-personal-accounts-data>
- Mastodon moving archives from tar.gz to ZIP, and >4 GB reports:
  <https://github.com/tootsuite/mastodon/issues/9318>
- Data Transfer Initiative principles (user-centered design, security, transparency):
  <https://dtinit.org/assets/dtp-overview.pdf>
- Google Data Portability API (archive job states, cancel/retry):
  <https://developers.google.com/data-portability/reference/rest>
- ZIP64 limits and `STORED` entry requirements (CRC and size known upfront):
  <https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/archivers/zip/ZipArchiveOutputStream.html>
