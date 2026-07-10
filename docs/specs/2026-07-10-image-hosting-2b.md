# Image hosting — sub-project 2b: server-side ingestion (mode B)

Date: 2026-07-10
Status: approved design, pending implementation plan
Depends on: the persistent task queue (sub-project 1, merged) and the canonical-image layer
(sub-project 2a, merged). This spec is **2b** of the image-hosting effort.

## 1. Goal

Let a pin's canonical image be **fetched by the server from a source URL**, asynchronously, via
the background task queue. This is "mode B": the user supplies a URL (they have the URL but not
the image bytes on their client), and the server downloads the referenced image, validates and
measures it exactly as a direct upload, stores it, and attaches it to the pin. The whole lifecycle
is covered: request, fetch, success, failure (diagnosable), replace, cancel, and cascade with the
pin's own deletion.

### Why mode B exists (the CORS argument)

Mode A (2a, direct multipart upload) assumes the client already has the image bytes. That is true
for a file upload or a browser extension operating on an already-decoded image. It is **not** true
for a plain "paste an image URL" flow: a browser can *display* a cross-origin image via `<img src>`
but generally **cannot read its bytes** (`fetch()` yields an opaque response under `no-cors`; a
canvas becomes tainted). So a web-UI-only user who pastes a URL cannot produce uploadable bytes
client-side. A server is not bound by CORS, so server-side fetch is the only way to turn a bare URL
into a stored image. Mode A and mode B fail on **complementary** cases: client-side fetch fails on
CORS (public sites with strict CORS), server-side fetch fails on authentication (private sites,
the "bounce" case). Both are needed.

## 2. Scope

**In scope (2b):**

- **Mode B request**: `PUT /api/v1/pins/{pinId}/image` accepts, in addition to 2a's multipart
  body, a JSON body `{ "sourceUrl": "..." }` that asks the server to fetch the image. Async,
  returns `202 Accepted`.
- The `pin.download` `TaskHandler` (the first concrete handler on the queue) that performs the
  fetch, reusing 2a's `ImageStore` / `ImageProbe` / `ImageRepository`.
- An `ImageDownload` sidecar entity + table tracking the in-flight / failed state of a mode-B
  fetch, and the **observable image state machine** (NONE / PENDING / READY / FAILED) surfaced via a
  dedicated status sub-resource for a diagnosable failure experience.
- A new `ImageFetcher` port + `api-fetch-http` adapter (JDK HTTP client) with a **Standard SSRF
  guard** (scheme allowlist + private/loopback/link-local/reserved/metadata IP rejection, per
  redirect hop).
- Completion of the **transactional-outbox seam** left open by sub-project 1: enqueue joining a
  caller's transaction, so "set download PENDING + enqueue task" and "insert image + delete
  download row" are each atomic.
- A small, additive extension to the `TaskHandler` contract: the handler receives the current
  attempt / max-attempts (needed to make an exhausted retry terminal; see §8, §14).
- Mode-B **replace** of an already-present image with zero serving downtime.
- Lifecycle cascade: cancel an in-flight download and drop its sidecar row when the pin is
  permanently deleted.

**Out of scope (deferred; some seams left open):**

- **Server-side format conversion.** Deliberately never built: if a user saw an image, their
  client supports its format. The stored bytes are the original, as in 2a.
- **Renditions / thumbnails** (a follow-up sub-project; a queue consumer under `<data_dir>/cache/`,
  resize-only, format preserved).
- **Perceptual `ImageHash`** (deferred until the pin-deduplication feature is specified).
- **Object storage** backends (the `ImageStore` port already leaves this open).
- **DNS-rebinding protection** (consciously accepted gap: the deployment infra is trusted, DNS is
  not attacker-controlled; §10).
- **Server-push status** (SSE / websockets): the client **polls** the image status endpoint. Push is
  a backward-compatible future addition.

## 3. Key decisions (rationale captured for the plan)

- **Mode B is triggered on the image resource, not on pin creation.** Pin creation stays
  image-agnostic. `PUT /pins/{id}/image` negotiates by `Content-Type`: `multipart/form-data` =
  mode A (bytes, synchronous, `200`/`201` as in 2a); `application/json` `{ "sourceUrl": "..." }` =
  mode B (async, `202`). Same verb, same resource ("make the canonical image be this"), two
  representations. Mode A remains the obvious fallback for the bounce case: re-PUT the bytes.
- **`sourceMediaUrl` is pure provenance.** It is never auto-fetched. The mode-B fetch target comes
  from the PUT body (`sourceUrl`), which is conceptually distinct from provenance (they may carry
  the same value). This keeps the browser-extension case clean (it has the bytes *and* the source
  URL, and must not trigger a download).
- **The fetch state lives in a sidecar `ImageDownload` entity, not on `Pin`.** A download request
  is a real concept with its own lifecycle (a source URL, transient, retryable, failable, cancelled
  on pin deletion). Keeping it out of `Pin` preserves the focused `Pin` entity and the immutable
  `Image` invariant from 2a.
- **The download row is deleted on success**, in the same transaction as the image insert (an
  atomic swap). "READY" is already carried by the `image` row, provenance by `pin.sourceMediaUrl`,
  timestamp by `image.createdAt`; a retained `COMPLETED` row would be redundant and would add a
  case to every state derivation.
- **Mode-B replace is supported with zero downtime.** While a replacement fetch runs, the old
  image keeps being served; the `image` and `download` rows coexist; on success the swap is atomic;
  on failure the old image stays and the replacement failure is surfaced.
- **SSRF guard: Standard, in the adapter.** Scheme allowlist (pure policy, may live in the domain)
  plus IP-range rejection resolved at fetch time (I/O, in the adapter), on the initial URL and every
  redirect hop.
- **HTTP client: the JDK `java.net.http.HttpClient`.** Zero new dependency (supply-chain hygiene),
  native streaming, and manual redirect control (`followRedirects(NEVER)`) so each hop is
  re-checked against the SSRF guard.
- **The handler is split across layers.** A thin `TaskHandler` bean in `api-presentation-quarkus`
  (which hosts the queue runtime and has Jackson) parses the JSON payload and delegates to a pure
  `DownloadPinImage` use case in `api-usecases` (orchestration over ports only). This mirrors how
  controllers adapt I/O formats and call pure use cases; it keeps a serialization dependency out of
  `api-usecases`.
- **Polling, not push.** Single-node self-hosted; the client polls the image status endpoint until it
  flips to READY or FAILED. Simple and robust.

## 4. Modules (dependency DAG preserved)

| Module | Role in 2b | May depend on |
|---|---|---|
| `api-domain` (pure) | `ImageDownload` entity + `DownloadStatus`/`DownloadReason`; `ImageFetcher` port + typed fetch exceptions; `ImageDownloadRepositoryInterface`; `TransactionRunner` port; the `TaskContext` value carried into handlers | nothing |
| `api-usecases` | `DownloadPinImage` (the fetch orchestration), `RequestPinImageDownload` (the mode-B PUT use case: atomic create-download + enqueue), image-state derivation; extended `TaskHandler` contract | `api-domain` |
| `api-persistence-sqlite` | `ImageDownloadModel` + mapper + `EbeanImageDownloadRepository`; migration `1.5`; `TransactionRunner` impl; make `enqueue` + repos ambient-transaction-aware (outbox) | `api-domain`, `api-utilities` |
| **`api-fetch-http`** (new) | implements `ImageFetcher` (JDK HTTP client, SSRF guard, redirect handling) | `api-domain` |
| `api-presentation-quarkus` | the `pin.download` `TaskHandler` bean (JSON parse + delegate), PUT content-negotiation (multipart vs JSON), the `GET .../image/status` endpoint + `PinImageStateDto`, download config; pass `TaskContext` from `TaskProcessor` | `api-usecases`, `api-domain` |
| `api-application` | composition root (`ImageFetcher` producer) + integration tests | all modules |

`api-usecases` depends only on **ports**, never on `api-fetch-http`. `api-application` wires the
adapter, as it does for `ImageStore` (2a).

## 5. Domain model

```kotlin
// api-domain/entities/ImageDownload.kt
data class ImageDownload(
    val pinId: UUID,               // identity: one canonical-image download per pin
    val sourceUrl: String,         // the fetch target from the PUT body
    val status: DownloadStatus,    // PENDING | FAILED (no COMPLETED: deleted on success)
    val reasonCode: DownloadReason?, // set iff status == FAILED
    val lastError: String?,        // truncated diagnostic from the last transient failure
    val taskId: UUID,              // the enqueued pin.download task id (for CancelTask on hard-delete)
    val requestedAt: Instant,
    val updatedAt: Instant,
)

enum class DownloadStatus { PENDING, FAILED }

enum class DownloadReason {       // the user-facing failure taxonomy (§9)
    URL_NOT_ALLOWED, UNREACHABLE, ACCESS_DENIED, NOT_FOUND,
    TOO_LARGE, INVALID_IMAGE, TOO_MANY_PIXELS, INTERNAL_ERROR, FETCH_FAILED,
}
```

**`ImageFetcher` port** (in `api-domain`):

```kotlin
interface ImageFetcher {
    // Applies the scheme allowlist + per-hop SSRF checks, follows redirects (capped),
    // requires a 2xx response, and returns the body stream for staging. Throws a typed
    // FetchException (see below) on any failure. Does not read/validate image content
    // (that is ImageProbe's job).
    fun openStream(sourceUrl: String): InputStream
}
```

**Fetch exceptions** (in `api-domain`, sealed): a `FetchException` family the handler maps to
`(DownloadReason, retryable?)`:

- Permanent: `UrlNotAllowedException`, `FetchAccessDeniedException` (401/403),
  `FetchNotFoundException` (404/410), `FetchTooLargeException` (declared or streamed size over
  limit), `TooManyRedirectsException`, `FetchFailedException` (other permanent 4xx).
- Retryable: `FetchUnreachableException` (DNS, connect refused, timeout, TLS, 5xx, 429).

`ImageTooLargeException` (store-side, from 2a) and the `ImageProbeException` family (2a) are reused
for the size/probe failures.

**`TransactionRunner` port** (in `api-domain`): `fun <T> inTransaction(block: () -> T): T`. The
persistence adapter implements it (Ebean explicit transaction on the single connection). Use cases
group multiple port writes into one atomic unit without importing Ebean. Repositories and
`enqueue` join the ambient transaction when one is active (this closes the sub-project-1 outbox
seam; §11).

**`Pin` changes:** none to the entity. The observable image state is derived (§6), not stored on
`Pin`.

**`TaskHandler` change** (§14): `fun handle(payload: String, context: TaskContext)` where
`data class TaskContext(val attempt: Int, val maxAttempts: Int)`.

## 6. Observable image state machine

The client never sees the queue. It observes the pin's image state, derived from two rows:

| `image` row | `download` row | Served bytes (`GET .../image`) | Reported status |
|---|---|---|---|
| absent | absent | `404` | **NONE** |
| absent | `PENDING` | `404` | **PENDING** |
| absent | `FAILED` | `404` | **FAILED** (+ `reasonCode`, message) |
| present | absent | `200` stream | **READY** |
| present | `PENDING` | `200` (old image) | **READY** + `replacement: { status: PENDING }` |
| present | `FAILED` | `200` (old image) | **READY** + `replacement: { status: FAILED, reasonCode, message }` |

The two precedence rules:

- **Bytes to serve:** the `image` row wins. If it exists, `GET .../image` streams it (`200`), even
  during a replacement fetch (zero downtime).
- **Status to show:** the `download` row's in-flight/failed state is always visible. When an image
  already exists, it surfaces as a `replacement` sub-object rather than as the primary status.

A leftover `PENDING` download row alongside a present image (e.g. after a crash mid-swap) is benign:
bytes still serve from the image; the row is superseded by the next successful swap or cleared by a
mode-A replace.

## 7. REST API

### `PUT /api/v1/pins/{pinId}/image` — set or replace (content-negotiated)

Owner-only (`403` non-owner, `404` missing pin), as in 2a.

- **`Content-Type: multipart/form-data`** → mode A, essentially unchanged from 2a: synchronous stage
  → probe → store → `201 Created` (was image-less) / `200 OK` (replaced). Additionally **cancels any
  in-flight download and clears the pin's `download` row** (PENDING or FAILED), so a direct upload wins
  over a slow or failing fetch (recovery from a bounce). A concurrent in-flight fetch is made a safe
  no-op by the handler's CAS-on-PENDING (§8): once the row is cleared, its swap finds no PENDING row.
- **`Content-Type: application/json`**, body `{ "sourceUrl": "<http(s) url>" }` → mode B:
  - Validates the URL shape (non-blank, `http`/`https`). Malformed → `400`.
  - Atomically (one transaction): upsert the `ImageDownload` row to `PENDING` with the `sourceUrl`,
    enqueue a `pin.download` task, store the returned `taskId` on the row (§11).
  - `202 Accepted` with the image status DTO (`status: PENDING`) and a `Location` header pointing at
    the status sub-resource (below).
  - If the pin already has a READY image, this is a **replacement request**: the row is created
    PENDING alongside the existing image (§6); still `202`.
  - If a mode-B download is already PENDING for the pin, the request is **coalesced** (dedup by
    `pinId`; see §11) and returns `202` for the in-flight download rather than enqueuing a second.
    (v1: coalescing applies even if the new `sourceUrl` differs; superseding an in-flight URL is
    future work — cancel via `DELETE` first, then re-request.)

### `GET /api/v1/pins/{pinId}/image` — serve (unchanged from 2a)

`200` streaming when a READY image exists (honouring `If-None-Match`/`ETag`/`304`); `404` otherwise
(NONE / PENDING / FAILED). The byte endpoint stays representation-free; status lives in the dedicated
status sub-resource (below).

### `GET /api/v1/pins/{pinId}/image/status` — the image state (poll target)

Owner-only. Returns a `PinImageStateDto` derived from the pin's `image` and `download` rows (§6).
Fields: `status` (NONE / PENDING / READY / FAILED); when READY, the image `url` + `mimeType` +
`width` + `height` + `byteSize`; when FAILED, the `reasonCode` + a human `message`; when READY with a
mode-B replace in flight or failed, a `replacement` sub-object carrying `status` (PENDING / FAILED)
plus `reasonCode` + `message` on failure.

`200` always (even for `NONE`); `403` non-owner; `404` missing pin. The web UI polls this after a
`202` until `status`/`replacement.status` settles. `PinOutputDto` and the pin list are **unchanged**
(no image block): the byte grid uses `GET .../image` directly, and only the mode-B diagnostic flow
needs this endpoint. Poll cadence is a client concern.

### `DELETE /api/v1/pins/{pinId}/image` — remove (2a, extended)

Deletes the READY image (row + file) as in 2a, and additionally cancels any in-flight download and
drops the `download` row for the pin (so a delete during a fetch leaves nothing pending). `204` /
`403` / `404` as in 2a.

## 8. The download flow (handler)

The `pin.download` handler (thin presentation bean → `DownloadPinImage` use case) mirrors 2a's
`SetPinImage`, with fetch replacing the multipart stream:

1. Parse payload `{ pinId, sourceUrl }`. Load the pin. **Pin gone** (hard-deleted between enqueue and
   run) → the download is moot; treat as a completed no-op (the row was already dropped by the
   cascade; §12). Read current `maxBytes` / `maxPixels` from config (not from the payload).
2. `stream = imageFetcher.openStream(sourceUrl)` — SSRF-checked, redirect-resolved, 2xx-verified.
3. `staged = imageStore.stage(stream, maxBytes)` — the same streaming stage as mode A (single-pass
   size cap + SHA-256; aborts over `maxBytes`).
4. `probe = imageProbe.probe(staged, maxPixels)` — validate + measure. On probe failure:
   `imageStore.discard(staged)`; permanent (INVALID_IMAGE / TOO_MANY_PIXELS).
5. Build `Image` (`storageKey = "originals/${pin.author.id}/$pinId/$imageId.${format.extension}"`,
   `createdAt = clock.now()`) and `imageStore.promote(staged, storageKey)`.
6. **Atomic swap** (`TransactionRunner.inTransaction`): `imageRepository.save(image)` **and**
   `imageDownloadRepository.deleteIfPending(pinId)` (CAS: delete only if a PENDING row exists). If the
   CAS deletes nothing (pin hard-deleted, or a competing runner already swapped), the promoted file is
   discarded (`imageStore.delete(storageKey)`) and the run is a no-op success. On any failure in this
   step, clean up both the staged temp and the promoted file (as 2a's `SetPinImage` does).
7. Return normally → the task is `SUCCEEDED`. Best-effort delete of a superseded old file (replace
   case), as in 2a.

**Failure handling** maps each exception to `(DownloadReason, retryable?)` (§9) and writes the
`download` row via a **CAS on `status = PENDING`**:

- Permanent → `imageDownloadRepository.markFailed(pinId, reason)` (CAS), then throw
  `PermanentTaskException(reason.name)` → task `DEAD`, row `FAILED`.
- Retryable → if `context.attempt >= context.maxAttempts` (last attempt): `markFailed(pinId, reason)`
  + throw `PermanentTaskException` (terminal, row `FAILED`); else `recordLastError(pinId, message)`
  (keeps `PENDING`) + rethrow → the processor reschedules with backoff.

**Idempotency (at-least-once).** A lease-expiry re-claim can execute the handler twice. Safety rests
on: bytes go temp → promote → atomic CAS-swap (a second run's swap finds no PENDING row and discards
its promoted file); every `download`-row write is CAS-guarded on `status = PENDING`, so a zombie's
late `FAILED` write no-ops once the row is gone; and the single-connection SQLite writer serializes
all settle/swap/cascade transactions. This satisfies the sub-project-1 idempotency contract (§8 of the
queue spec).

## 9. Failure taxonomy

| Cause | Retryable? | `reasonCode` | User-facing message |
|---|---|---|---|
| URL blocked (SSRF: private IP, disallowed scheme, redirect to blocked target) | No | `URL_NOT_ALLOWED` | This URL is not allowed. |
| Unreachable (DNS, connection refused, timeout, TLS, 5xx, 429) | **Yes** → on exhaustion | `UNREACHABLE` | The server could not reach this URL. |
| Access denied (401 / 403) — the bounce case | No | `ACCESS_DENIED` | The site refused the server access. Upload the image directly. |
| Not found (404 / 410) | No | `NOT_FOUND` | No image at this URL. |
| Too large (> `max_file_bytes`) | No | `TOO_LARGE` | Image too large. |
| Not a supported image (probe) | No | `INVALID_IMAGE` | The content is not a supported image. |
| Too many pixels (> `max_pixels`) | No | `TOO_MANY_PIXELS` | Dimensions too large. |
| Internal error (disk / DB) | **Yes** → on exhaustion | `INTERNAL_ERROR` | Temporary error, try again later. |
| Other permanent HTTP / redirect loop | No | `FETCH_FAILED` | The download failed. |

TLS errors are folded into `UNREACHABLE` (retryable) for simplicity, at the cost of a few wasted
retries. Messages are illustrative; final copy avoids long dashes and stays plain.

## 10. SSRF guard (Standard, in the adapter)

For the initial URL and **every redirect hop** (`followRedirects(NEVER)` + a manual, capped loop):

1. **Scheme allowlist:** `http` / `https` only (hard-coded). Others (`file`, `ftp`, `gopher`, …) →
   `UrlNotAllowedException`. This step is pure policy and may live in `api-domain`.
2. **Host resolution + IP-range rejection** (I/O, in the adapter): resolve the host; reject if the
   resolved address is loopback (`127.0.0.0/8`, `::1`), private (`10/8`, `172.16/12`, `192.168/16`,
   `fc00::/7`), link-local (`169.254.0.0/16`, `fe80::/10`, incl. the cloud-metadata `169.254.169.254`),
   or otherwise reserved/unspecified/multicast → `UrlNotAllowedException`.
3. **Redirect cap:** at most `images.download.max_redirects` hops; exceeding → `TooManyRedirectsException`
   (permanent `FETCH_FAILED`).

**DNS-rebinding is a consciously accepted gap.** Between the resolve-check and the actual connect the
JDK client re-resolves DNS, so a host controlling DNS could pass the check with a public IP and connect
to a private one. We accept this because the deployment infra is trusted and DNS is not
attacker-controlled; the mitigation (pin the checked IP for the connection) is a future hardening.

## 11. Transactional outbox and dedup

Two transitions must be atomic; both use `TransactionRunner.inTransaction`, and both rely on
`enqueue` / the repositories joining the ambient transaction (the sub-project-1 seam this closes):

- **Request (mode-B PUT):** upsert `download` → `PENDING`, `enqueue` the `pin.download` task, store the
  returned `taskId` on the row — one transaction. Either all commit or none: never a PENDING row with
  no task, nor a task with no row.
- **Success swap (handler):** insert `image` + delete the `download` row — one transaction (§8).

**Dedup:** the enqueue uses `dedupKey = "pin.download:" + pinId` so a second live mode-B request for a
pin coalesces onto the in-flight download (the queue's partial-unique dedup index, sub-project 1). The
`download` row's `pinId` PK enforces one download per pin at the table level.

**Implementation note.** Sub-project 1 currently has `enqueue` open its own transaction. 2b makes it
ambient-transaction-aware (join `database.currentTransaction()` when present, else open its own),
which is exactly the deferred outbox variant. The single-connection datasource makes "ambient
transaction" unambiguous.

## 12. Lifecycle cascade

- **Soft-delete (recycle bin):** the download is **left running** (mirrors 2a leaving the image on a
  soft-deleted pin). If it completes, the recycled pin has its image; on restore it is present.
  Cancelling would lose the download without re-triggering it on restore.
- **Permanent delete of a pin / emptying the recycle bin:** within the existing hard-delete
  transaction, in addition to 2a's image cascade — `CancelTask(taskId)` (from the `download` row) and
  delete the `download` row. This is precisely the `CancelTask` use case sub-project 1 anticipated
  ("cancel a pin's pending download when the pin is hard-deleted").
- **Race with an in-flight fetch:** the single-connection SQLite writer serializes the hard-delete
  transaction and the handler's swap transaction, and the swap is a CAS on the PENDING `download` row
  the hard-delete just removed. If the hard-delete commits first, the swap finds no PENDING row,
  discards the promoted file, and no-ops. The same CAS that guards zombie writes guards this race.

## 13. Persistence

New `image_download` table (migration `1.5`, generated via
`:api-persistence-sqlite:generateDbMigration`, hand-reviewed as in 2a):

| Column | Type | Notes |
|---|---|---|
| `pin_id` | TEXT PK | UUID; one download per pin (PK = uniqueness) |
| `source_url` | TEXT NOT NULL | the fetch target |
| `status` | TEXT NOT NULL | `PENDING` \| `FAILED` |
| `reason_code` | TEXT NULL | set iff `status = 'FAILED'` |
| `last_error` | TEXT NULL | truncated last transient diagnostic |
| `task_id` | TEXT NOT NULL | UUID; the enqueued `pin.download` task id |
| `requested_at` | INTEGER NOT NULL | epoch ms |
| `updated_at` | INTEGER NOT NULL | epoch ms |

No enforced FK (the project-wide `foreign_keys` pragma is off; FKs are decorative — 2a pitfall). No
Ebean `@Version`: the CAS on `status = 'PENDING'` plus the single-writer serialization is the
concurrency guard (consistent with 2a's insert/delete-only `images` table omitting `@Version`).

## 14. `TaskHandler` contract extension (sub-project 1 touch)

`fun handle(payload: String, context: TaskContext)` with `TaskContext(attempt, maxAttempts)`. The
`TaskProcessor` already holds `attempts` / `maxAttempts` on the `ClaimedTask`; it constructs the
context and passes it. Rationale: a handler always knows a *permanent* failure itself, but only the
processor knows whether a *retryable* failure is the last attempt; without the context, an exhausted
retryable leaves the queue task `DEAD` while the handler never wrote the terminal `FAILED` state.
Passing the attempt makes the single source of truth (the task) available to the handler, which can
then promote a last-attempt retryable to terminal.

Low risk: there are **no existing `TaskHandler` implementations** (the `pin.download` handler is the
first), so the signature change breaks nothing. The queue stays generic (it learns nothing about
pins). The change benefits every future handler (e.g. the follow-up thumbnailing handler).

## 15. Configuration

New group, same `@ConfigMapping` (SNAKE_CASE, `@WithDefault`) pattern as `ImagesConfig` /
`TaskQueueConfig`:

- `images.download.connect_timeout` — default `PT5S`.
- `images.download.request_timeout` — total request budget, default `PT30S`.
- `images.download.max_redirects` — default `5`.

Reuses `images.max_file_bytes` / `images.max_pixels`. Allowed schemes are hard-coded (`http`/`https`).
**Invariant** (ideally validated at boot): `connect_timeout + request_timeout < tasks.lease_duration`
(default `PT1M`), so a download is never reaped mid-flight (which would cause a double-run). Defaults
give `35s < 60s`.

## 16. Testing strategy (TDD, project order)

100% branch coverage per package (hard rule) across all in-gate modules, including the new
`api-fetch-http`. Deterministic: inject the clock, drive the queue with the existing test seams,
never `Thread.sleep`; the `ImageFetcher` is a port, so use cases test against a fake.

1. **Integration** (`api-application`, REST Assured, real libvips + a local stub HTTP origin):
   mode-B PUT → `202` → poll to READY (round-trip via a local origin serving a fixture); the bounce
   case (origin returns 403) → FAILED `ACCESS_DENIED`; `404` → FAILED `NOT_FOUND`; a non-image body
   → FAILED `INVALID_IMAGE`; mode-B replace of a mode-A image (old bytes served throughout, then
   swapped); mode-A PUT clears a FAILED download; DELETE during PENDING cancels; permanent-delete of
   the pin during PENDING cancels + drops the row; content-negotiation (multipart vs JSON) on the
   same PUT.
2. **Use-case** (`api-usecases`, MockK): `DownloadPinImage` orchestration and every failure branch;
   the attempt-context terminal-promotion (retryable at `attempt < max` reschedules; at
   `attempt >= max` writes FAILED + throws permanent); the CAS no-ops (swap finds no PENDING row);
   `RequestPinImageDownload` atomic create+enqueue (via `TransactionRunner` fake / spy).
3. **Adapters**:
   - `api-fetch-http` (`ImageFetcher`): scheme rejection; each blocked IP range (loopback / private /
     link-local / metadata) rejected on the initial URL **and** on a redirect target; redirect cap;
     2xx body returned; 401/403/404/5xx mapping; a local origin for the happy path. Both sides of
     every guard branch.
   - `EbeanImageDownloadRepository` (`RepositoryTest`, real SQLite): upsert PENDING; `markFailed`
     CAS (updates a PENDING row, no-ops a missing/non-PENDING row); `deleteIfPending` CAS;
     `findByPinId`; ambient-transaction join (create-download + enqueue in one tx, rollback leaves
     neither).
   - `TransactionRunner` impl: commit and rollback paths.
4. **Queue extension**: `TaskProcessor` passes the correct `TaskContext`; both attempt branches
   covered; existing queue tests updated for the new `handle` signature.

## 17. Risks to verify during implementation

- **Streaming body timeout.** The JDK `HttpRequest.timeout()` bounds obtaining the response, not
  necessarily the streaming read of the body. Ensure the body read is time-bounded (a slow-loris
  origin must not hang a worker). Confirm the exact behaviour via context7 (`java.net.http`).
- **Multipart-vs-JSON content negotiation on the same `PUT`** with RESTEasy Reactive — confirm one
  method can dispatch on `Content-Type` (or two methods on the same path with distinct `@Consumes`).
- **JDK client manual redirect handling** — extracting `Location`, resolving relative redirects,
  re-checking each hop; confirm against current docs.
- **SSRF completeness** — the IP-range set (IPv4 and IPv6) and the resolution point; test each range.
- **Ambient-transaction join** — verify Ebean joins `currentTransaction()` for both `enqueue` and the
  repositories on the single connection, so the outbox atomicity actually holds (the sub-project-1
  plan/text and merged code disagreed on this; verify empirically with a rollback test).
- **`request_timeout + connect_timeout < lease`** — enforce/validate so downloads are not reaped.

## 18. Future seams (left open, not built in 2b)

- **Renditions / thumbnails** (follow-up): a queue consumer generating resize-only, format-preserved
  derivatives under `<data_dir>/cache/`, keyed by `(image id + size spec)`; served via `?size=`
  negotiation on the byte endpoint (backward-compatible: no param = canonical). No format conversion,
  ever.
- **Perceptual `ImageHash`** — added with the pin-deduplication feature.
- **Server-push status** (SSE / websockets) — an alternative to polling; the state machine is
  unchanged.
- **DNS-rebinding hardening** — pin the SSRF-checked IP for the actual connection.
- **Object storage** `ImageStore` backend — the port already allows it.
