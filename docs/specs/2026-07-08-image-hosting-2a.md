# Image hosting — sub-project 2a: the canonical image

Date: 2026-07-08
Status: approved design, pending implementation plan
Depends on: the persistent task queue (sub-project 1, merged) and the JDK 25 baseline
(merged). This spec is **2a** of the image-hosting effort; **2b** (server-side download
via the queue, perceptual hashes, renditions) is out of scope here.

## 1. Goal

Let a pin own **one canonical image**, uploaded directly by its owner (multipart), stored
on the filesystem, and served back. Cover the full lifecycle: set, replace, serve, delete,
and cascade with the pin's own deletion.

## 2. Scope

**In scope (2a):**

- A first-class `Image` domain entity; a `Pin` references at most one canonical `Image`.
- Direct multipart upload (**mode A**): `PUT /api/v1/pins/{pinId}/image`.
- Serving: `GET /api/v1/pins/{pinId}/image` with HTTP caching.
- Removal: `DELETE /api/v1/pins/{pinId}/image` (leaves the pin image-less).
- Replacement of the canonical image with a higher-quality one.
- Full-decode validation and measurement of uploaded bytes (format, dimensions).
- Filesystem storage behind a swappable port; image decoding behind a swappable port.

**Out of scope (deferred; seams are left open, see §16):**

- **Mode B** (server fetches the image from a URL via the task queue: the `pin.download`
  `TaskHandler`).
- **Renditions** (thumbnails, format conversions) and their serving/negotiation.
- **Perceptual hashes** (`ImageHash`, pHash/dHash) for the future pin-merge feature.
- Object storage (S3-like) backends. The `ImageStore` port leaves this open.

## 3. Key decisions (rationale captured for the plan)

- **A pin has one canonical image, not a list.** Multiple representations (thumbnails,
  format conversions) are a derived, regenerable **optimisation cache**, not a domain
  concept. They will be modelled outside the domain when built (§16). So the domain carries
  `Pin.image: Image?`, singular.
- **`Image` is immutable.** Every upload creates a fresh `Image` with a new id; nothing is
  mutated in place. Replacement is a swap (§10). This keeps a clean invariant and makes
  future rendition-cache invalidation trivial (the old image id is simply orphaned).
- **Bytes never live in the database.** They live on the filesystem; the DB row holds only
  metadata plus a storage key. (Project-wide rule.)
- **Server-controlled file names.** Never the uploaded filename (path-traversal / polyglot
  attack surface). The name is derived from the image id and the validated format.
- **Storage and decoding are ports** in `api-domain`, each with its own adapter module, so
  the filesystem backend can later be swapped for object storage and the imaging backend can
  be swapped without touching callers.
- **libvips (via vips-ffm) is the imaging backend.** Chosen for its performance, low memory,
  full format coverage (including animated WebP), and fitness for the future rendition
  pipeline. This drove the JDK 25 baseline (already merged): vips-ffm needs a JDK 23+ runtime,
  native `libvips`, and `--enable-native-access=ALL-UNNAMED` (already granted).
- **403 vs 404 semantics.** UUIDs are already opaque, so we do not hide existence: a
  non-owner acting on someone else's pin gets `403`; a missing pin or a pin without an image
  gets `404`.

## 4. Modules (dependency DAG preserved)

| Module | Role in 2a | May depend on |
|---|---|---|
| `api-domain` (pure) | `Image` entity; `Pin.image` + nullable `sourceMediaUrl`; ports `ImageStore`, `ImageProbe`, `ImageRepositoryInterface`; `ImageFormat`; domain errors | nothing |
| `api-usecases` | set/replace/delete use cases; wiring image deletion into pin deletion | `api-domain` |
| `api-persistence-sqlite` | `ImageModel`, migration, `EbeanImageRepository`, mappers; triggers file deletion on pin permanent-delete | `api-domain`, `api-utilities` |
| **`api-storage-filesystem`** (new) | implements `ImageStore` (filesystem I/O) | `api-domain` |
| **`api-imaging-vips`** (new) | implements `ImageProbe` (libvips via vips-ffm) | `api-domain` |
| `api-presentation-quarkus` | REST controller, DTOs, config, CDI wiring | `api-usecases`, `api-domain` |
| `api-application` | composition root + integration tests | all modules |

`api-usecases` depends only on the **ports**, never on the two new adapter modules.
`api-application` (composition root) wires the adapters.

## 5. Domain model

```kotlin
// api-domain/entities/Image.kt
data class Image(
    override val id: UUID,
    val pinId: UUID,
    val mimeType: String,      // validated: image/png | image/jpeg | image/webp | image/gif
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val contentHash: String,   // SHA-256 hex of the stored bytes
    val storageKey: String,    // "originals/<user_id>/<pin_id>/<image_id>.<ext>"
    val createdAt: Instant,
) : Identifiable
```

`Pin` changes:

- `sourceMediaUrl: String?` becomes **nullable** (absent for a direct upload; retained as
  provenance for a bookmarked pin or a future mode-B pin).
- gains `image: Image?` (the canonical image; `null` until set).

`ImageFormat` enum in `api-domain` maps each supported format to its MIME type and file
extension (the single source of truth for the `.<ext>` suffix and the `Content-Type`).

## 6. Ports (in `api-domain`)

**`ImageStore`** — filesystem-agnostic byte storage:

- create a temp artifact under the data dir's `tmp/` and stream bytes into it, computing
  `byteSize` and `content_hash` (SHA-256) in a single pass; `fsync` it;
- promote a temp artifact to a final `storageKey` (move to a fresh path);
- open a read stream for a `storageKey` (for serving);
- delete a `storageKey` (idempotent: a missing file is not an error).

**`ImageProbe`** — validate and measure image bytes:

- given a readable source, return `(format, width, height)` or reject with a typed error
  (unsupported format / not decodable / exceeds the pixel limit).
- Reads dimensions from the header first (cheap) to guard against decompression bombs, then
  fully decodes to confirm validity. Does **not** re-encode.

**`ImageRepositoryInterface`** — persistence port:

- `save(image)` create-or-replace in a single transaction (delete the pin's existing row,
  insert the new one; `pin_id` is unique);
- `findByPinId(pinId)`;
- `deleteByPinId(pinId)`.

## 7. Storage layout & crash-safety

Base directory is configurable (`images.data_dir`). Two independent trees:

- **Originals (durable):** `<data_dir>/originals/<user_id>/<pin_id>/<image_id>.<ext>`
- **Temp (same filesystem as originals):** `<data_dir>/tmp/…`
- **Cache (future, disposable):** `<data_dir>/cache/…` (renditions; not created in 2a)

Upload write ordering (crash-safe by construction):

1. Stream the multipart body to a **temp file** under `<data_dir>/tmp/`, computing byteSize +
   content_hash in one pass; enforce the size limit while streaming.
2. Probe (validate + measure) the temp file.
3. `fsync` the temp file, then **move** it to the destination — always a **fresh** path
   (`<image_id>.<ext>`), never overwriting an existing referenced file.
4. Only then commit the DB row (so a durable file precedes a committed reference).
5. After commit, best-effort delete of any file the operation superseded.

Because a destination path is never referenced by a committed row until after the move, a
crash during the move leaves at most a **benign orphan** in `tmp/` or an unreferenced fresh
file — never a committed row pointing at a torn file. **Atomicity of the move is opportunistic,
not required**: keeping temp on the same filesystem makes the move a rename (atomic) in
practice, which is nice for directory scanners/backups, but correctness rests on the ordering
above, not on the move being atomic. `fsync`-before-commit is the crash-critical guarantee
(durability, not atomicity).

## 8. REST API

All three endpoints are **owner-only**: the requester must be the pin's author.

### `PUT /api/v1/pins/{pinId}/image` — set or replace (multipart/form-data)

- `201 Created` when the pin had no image; `200 OK` when replacing.
- `403` if the requester is not the pin's owner; `404` if the pin does not exist.
- `413` if the body exceeds `images.max_file_bytes`.
- `422` if the bytes are not a decodable image of a supported format, or exceed
  `images.max_pixels`.
- Response body: `ImageOutputDto` (id, mimeType, width, height, byteSize, and the serve URL).
- `PUT` is the correct verb: "make the canonical image of this pin be these bytes"; it covers
  both create and replace. Multipart over `PUT` is valid HTTP. `PUT` is **not negotiable**: if
  RESTEasy Reactive needs coaxing to accept a multipart `PUT`, that is a technical detail to
  solve, not a reason to switch to `POST`. Raise it for discussion if it proves obstinate.

### `GET /api/v1/pins/{pinId}/image` — serve

- `403` for a non-owner; `404` if the pin has no image.
- `304 Not Modified` when `If-None-Match` equals the current `content_hash`.
- Otherwise `200` streaming the file with:
  - `Content-Type` from the stored `mimeType`,
  - `ETag` = `content_hash` (strong validator),
  - `Cache-Control: private` plus revalidation (**not** `immutable`: this URL is stable while
    its content can change on replacement),
  - `Content-Length` = `byteSize`.

### `DELETE /api/v1/pins/{pinId}/image` — remove

- Removes the canonical image (row + file), leaving the pin image-less.
- `204 No Content` on success; `403` for a non-owner; `404` if the pin has no image.

## 9. Serve, replace, delete flows

- **Serve:** look up `Image` by `pinId`; 404 if none; honour `If-None-Match`; stream via
  `ImageStore.openStream(storageKey)`.
- **Replace:** run the upload flow to a new `image_id`; in one transaction delete the old row
  and insert the new; after commit, best-effort delete the old file. The `Image` immutability
  invariant means the old id (and its future rendition cache) is simply orphaned.
- **Delete:** delete the row (commit), then best-effort delete the file.

## 10. Pin lifecycle cascade

- **Soft-delete (recycle bin):** the `Image` row and file are **kept** (the pin is restorable).
- **Permanent delete of a pin / emptying the recycle bin:** commit the DB deletion (pin, tags,
  image row), then best-effort delete the file(s). A crash leaves at most a benign orphan
  file, never a dangling reference. This wires into the existing
  `permanentlyDeletePin` / `permanentlyDeleteAllSoftDeletedPinsForUser` paths.

## 11. Validation & limits

- **Accepted formats:** PNG, JPEG, WebP (including **animated WebP**), GIF. Hard-coded in
  `ImageFormat` (the decoder must support them anyway); not configurable.
- **Validation depth:** full decode via `ImageProbe` (libvips). Header dimensions are read
  first to reject over-limit images before a full decode (decompression-bomb guard).
- **`images.max_file_bytes`** (default **30 MiB**): reject with `413`.
- **`images.max_pixels`** (default **50,000,000**, i.e. width × height of the canvas): reject
  with `422`.
- Original bytes are stored **as-is** (no re-encode), preserving animation.

## 12. Configuration (`@ConfigMapping`, snake_case, `@WithDefault`)

- `images.data_dir` — base directory; contains `originals/`, `tmp/`, and (future) `cache/`.
- `images.max_file_bytes` — default `31457280` (30 MiB).
- `images.max_pixels` — default `50000000`.

## 13. Persistence

- New `image` table: `id` (PK), `pin_id` (**unique**, FK to `pin`), `mime_type`, `width`,
  `height`, `byte_size`, `content_hash`, `storage_key`, `created_at`, plus Ebean's `@Version`.
  The unique `pin_id` enforces "one canonical image per pin" at the database level; replacement
  is delete-then-insert within one transaction.
- Migration widening `pin.source_media_url` to nullable.
- Ebean migration generated via `:api-persistence-sqlite:generateDbMigration`, with the new
  partial/foreign-key constraints reviewed by hand as with the queue migration.

## 14. Imaging backend (libvips via vips-ffm)

- Dependency: `app.photofox.vips-ffm:vips-ffm-core` in `api-imaging-vips`.
- Runtime prerequisites (already satisfied by the JDK 25 baseline): JDK 23+, native `libvips`
  installed, `--enable-native-access=ALL-UNNAMED` (granted for tests and the runtime image).
- 2a additions the plan must include: install native `libvips` in the CI `test`/`build-image`
  environments and in the deploy image (`Dockerfile`), and document the local-dev requirement.
- `ImageProbe` uses libvips to load the image (lazy header read for dimensions), reject on a
  libvips error or an unsupported loader, and report canvas width/height. Exact vips-ffm API
  usage is confirmed against current docs at implementation time (context7), including the
  animated-WebP path.

## 15. Testing strategy (TDD, project order)

1. **Integration** (`api-application`, REST Assured): upload → serve round-trip; replace;
   `403` for a non-owner; `413` for oversize; `422` for invalid / over-pixel; `404` for a pin
   without an image; `304` via `ETag`; `DELETE` then `404`; permanent-delete of the pin removes
   the file.
2. **Use-case** (`api-usecases`, MockK): orchestration and ordering (probe → store → commit →
   best-effort delete), and every error branch.
3. **Adapters**: `EbeanImageRepository` (`RepositoryTest`); `api-storage-filesystem`
   (temp→move, single-pass hash+size, idempotent delete, fsync best-effort);
   `api-imaging-vips` (real decode of PNG / JPEG / WebP / **animated WebP** / GIF fixtures, plus
   rejects for a non-image, an unsupported format, and an over-pixel image).
4. **100% branch coverage per package** (hard rule) across the in-gate modules, including the
   two new adapter modules.

## 16. Future seams (left open, not built in 2a)

- **Renditions cache:** a separate, disposable `<data_dir>/cache/…` tree keyed by
  `(canonical image id + transform spec)`. Kept out of the domain; generated by future queue
  tasks and/or on demand. The canonical image's stable id and the `originals/` vs `cache/`
  split already leave room.
- **Variant negotiation** on serve: format via the `Accept` header, size via a `?size=` query
  parameter. The 2a serve endpoint is parameterless (= canonical), so adding negotiation later
  is backward-compatible (no parameters = canonical).
- **`ImageHash`** (perceptual): added to the model when the computing feature is built.
- **Mode B** (`pin.download` `TaskHandler`): produces an `Image` for a pin from a URL, reusing
  this same storage/probe/persistence, with an idempotent download (temp → move) and a network
  timeout well under the queue lease.

## 17. Risks to verify during implementation

- **Animated WebP** support in the chosen vips-ffm / libvips version — confirm via context7 and
  a real animated fixture in the `api-imaging-vips` tests.
- **Multipart over `PUT`** with RESTEasy Reactive — confirm it works. `PUT` is the correct
  verb; if RESTEasy Reactive resists, raise it for discussion rather than downgrading to `POST`.
- **Streaming, not buffering** — probe and hash from the temp file, never hold a 30 MiB body in
  memory.
- **Native `libvips` availability** in CI and the deploy image — a missing native lib fails
  `api-imaging-vips` tests and the runtime.
