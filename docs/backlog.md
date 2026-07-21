# Backlog

**Living document.** A snapshot of what the API already ships and what is still open, ordered by priority.
Keep it alive: update it at the end of every sub-project (Wrap phase), and whenever a scope decision is made.
It is written in English (project language), like the specs, plans, and handoffs.

Last reviewed: 2026-07-22.

## How to use this file

- **Shipped** records what exists so the backlog reads against a known baseline. Move an item there when it merges.
- **Open items** are grouped by priority band, not by module. Each item states the context and what must be
  decided or done, with a pointer to the relevant spec/handoff when one exists.
- `P0` = product decisions that shape the data model and the UI; resolve these before building on top of them.
  `P1` = client ergonomics needed for the web UI and browser extension. `P2` = operational debt already flagged
  in handoffs (not UI blockers).
- When an item is picked up, note the branch/sub-project next to it; when it merges, move it to **Shipped**.

---

## Shipped (baseline)

Six sub-projects merged (`v0.1.0-task-queue` → `v0.5.0-boards`, plus **session-token authentication**,
now on `main`). CI green, 100% branch coverage, hexagonal layering, generated OpenAPI.

- **Users**: registration (`POST /api/v1/users`, public). Authentication is session-token / Bearer
  (see the Auth bullet); HTTP Basic has been removed.
- **Auth (session tokens)**: `POST /api/v1/sessions` login issues an opaque revocable bearer token
  (SHA-256 hash stored, `rememberMe` persistent/ephemeral TTL); `GET /me`, `GET /sessions/current`,
  `POST /sessions/current/renew` (atomic rotation), `DELETE /sessions/current` (logout),
  `DELETE /sessions` (logout-all); OpenAPI declares a bearer security scheme. Replaces HTTP Basic
  entirely. See `docs/handoffs/2026-07-21 - handoff - session-token-auth.md`.
- **Pins**: list (cursor pagination + sort strategy), get, create, update, soft-delete, tag.
- **Recycle bin**: list recycled, restore, permanent delete, empty.
- **Search**: pins (trigram similarity) + tags.
- **Images**: direct upload (multipart, mode-A), download-from-URL (mode-B, async via the task queue), serve
  original, delete, status, and disposable **renditions/thumbnails** (lazy WebP, on-disk cache, `?size` + `?animated`).
  See `docs/handoffs/2026-07-16 - handoff - image-hosting-3-renditions.md`.
- **Boards**: named owner-scoped collections; a pin belongs to 0..N boards (set-based membership via
  `PUT /pins/{id}/boards`, mirroring tags); board CRUD, cursor-paginated board pins, `PinOutputDto.boards`
  + board `pinCount`, and a recycle bin mirroring `PinRecycleBin`. On `main`.
  See `docs/handoffs/2026-07-20 - handoff - boards.md`.
- **CORS**: Quarkus CORS filter enabled with a whitelist policy for browser clients. Allowed origins
  driven by `api.cors.origins` (typed on `ApiConfig`; dev default `http://localhost:5173`, prod via
  `API_CORS_ORIGINS`); methods `GET,POST,PUT,DELETE`, request headers `Authorization,Content-Type`,
  `Location` exposed, credentials off (Bearer only), 24 h preflight cache. On `main`.
  See `docs/handoffs/2026-07-21 - handoff - cors.md`.
- **Profile management**: self-service `PUT /me/password` (verify current password, reject any
  previously-used password against the full history, revoke all sessions incl. the caller's, 204) and
  `DELETE /me` (step-up via `X-Reauthentication: password <base64url>`, then an async hard delete: a
  one-way Ebean `@SoftDelete` tombstone mirrored on `User.softDeleted`, revoke-all, enqueue
  `account.delete`; the worker erases rows in FK order + on-disk image bytes and frees the username,
  202). Password hashing inverted behind a `PasswordHasher` port (BCrypt adapter in `api-application`);
  all writes on the `TransactionRunner` port; `UserCreator` migrated off `@Transactional`. Migration
  1.9. Validated end-to-end (a real pin+image is seeded, the account deleted, and the username becomes
  re-registerable only after the worker completes). On `main`.
  See `docs/handoffs/2026-07-21 - handoff - profile-management.md`.
- **Infrastructure**: generic task queue (enqueue/cancel/reap), Ebean migrations, git hooks, CI gate.

---

## Open items

### P0 — (none open)

**Client auth story shipped 2026-07-21** (session tokens; merged to `main`). **CORS shipped 2026-07-21**
(merged to `main`, in Shipped above). **Profile management shipped 2026-07-21** (change password +
async account deletion, in Shipped above). Scheduled next: an architecture correction, extracting the
task worker runtime out of the presentation module (see P2).

### P1 — Client ergonomics (needed for the web UI and browser extension)

- **Browser-extension CORS origin.** Deferred from the CORS sub-project (decision B1): the extension
  does not exist yet and has no stable ID, so no origin is wired for it. When it ships, add its
  `chrome-extension://<id>` / `moz-extension://<id>` origin to `api.cors.origins`. See
  `docs/handoffs/2026-07-21 - handoff - cors.md`.
- **User data export / import (portability).** Let a user export **all** their data and re-import it, on this
  instance or another one, so they stay in control of their data and are never held hostage. Not yet specced.

### P2 — Operational debt (flagged in handoffs; not UI blockers)

- **Extract the task worker runtime into a dedicated adapter module.** *(Architecture correction —
  scheduled as the next sub-project, right after profile management. Flagged 2026-07-21.)* An entire
  task-worker **driving** subsystem currently lives in the HTTP presentation module
  (`api-presentation-quarkus/.../tasks/`): `TaskDispatcher` (poll loop), `TaskWorkerLifecycle`,
  `TaskRuntimeProducers` (CDI producers), `WorkerExecutor`, `TaskQueueConfig`, `TaskQueueMetrics`, and
  the task handlers (`PinDownloadTaskHandler`, and the `AccountDeletionTaskHandler` added by profile
  management — knowingly placed there temporarily). Tasks are a driving adapter, not HTTP presentation.
  Extract them into a new **`api-worker-quarkus`** module (depends on `api-usecases` + `api-domain`),
  mirroring the existing per-adapter modules (`api-storage-filesystem`, `api-imaging-vips`,
  `api-fetch-http`). **Exception:** `SystemClock` (the `Clock` port impl) is app-wide, not
  worker-specific, so it moves to a shared home (the `api-application` composition root or a shared infra
  module), **not** the worker module. `TaskProcessor`, the `TaskHandler` port, the registry, and
  `EnqueueTask` / `CancelTask` / `ReapExpiredTasks` are correctly in `api-usecases` and stay. Watch the
  Quarkus CDI bean-discovery wiring for the new module.

- **Consolidate misplaced infra/security adapters into a dedicated module.** *(Architecture cleanup. Flagged
  2026-07-21.)* Small, non-HTTP adapters are scattered: `SecureTokenGenerator` (the `TokenGenerator` /
  SecureRandom impl) and `SystemClock` (the `Clock` impl) sit in `api-presentation-quarkus`, and
  `BcryptPasswordHasher` (the `PasswordHasher` impl added by profile management) is placed in the
  `api-application` composition root as a pragmatic temporary home. Gather them into a dedicated adapter
  module (e.g. `api-security` / `api-system`) depending on `api-domain`, mirroring the per-adapter-module
  convention. Companion to the `api-worker-quarkus` extraction (both move misplaced adapters out).

- **Migrate the remaining `@Transactional` use cases to the `TransactionRunner` port.** *(Architecture
  cleanup. Flagged 2026-07-21.)* `SessionCreator` and `SessionRenewer` still carry
  `jakarta.transaction.@Transactional` — a persistence concern leaking into the application layer. Route
  them through the existing `TransactionRunner` domain port (as the image use cases and the new
  profile-management use cases do) and drop the annotation. `UserCreator` is migrated by profile
  management; only the `Session*` pair is left.

- **Enforce Clean Architecture boundaries with Konsist.** *(Architecture enforcement. Flagged 2026-07-21.)*
  Add Konsist (a Kotlin architecture-testing library) checks that fail the build when a layer imports what
  it must not: `api-usecases` pulling in `jakarta.transaction`, `io.ebean`, `jakarta.ws.rs`, or a concrete
  crypto/random library; `api-domain` importing any I/O; and the module dependency DAG. Makes the AGENTS.md
  dependency rules explicit and unavoidable in CI instead of relying on review discipline. Use the context7
  MCP for Konsist's current API when building this.

- **Expired session-token GC sweep.** `session_tokens` rows are inert once expired (verification rejects
  them), but they accumulate. No sweep in v1. See `docs/handoffs/2026-07-21 - handoff - session-token-auth.md`.

- **`animated` backfill migration** for pre-existing image rows. The only item with a correctness consequence:
  rows predating migration 1.6 are labelled `animated = false`, so `?animated=false` on such a row can serve the
  original animated bytes instead of flattening. See the renditions handoff, "NOT validated" section.
- **Cache GC sweep** for orphaned rendition subtrees. Eviction is best-effort; a failed eviction or a crash
  mid-write leaves a subtree forever. Spec §14 of the renditions sub-project.
- **Deleted-account residue GC.** If the `account.delete` task (profile management) fails partially or totally,
  an account can stay stuck in its Ebean soft-delete tombstone with orphaned child rows and on-disk files. A
  sweep should reclaim such tombstoned accounts and their residue. Note: the cleaner's disk loop wraps only
  `renditionCache.evictImage` in `runCatching`, not `imageStore.delete`, so a failed `imageStore.delete`
  propagates after the DB commit (the task then retries and no-ops, since the user is already gone), leaving
  byte residue; making the whole per-image cleanup best-effort would reduce this. New 2026-07-21.
- **Task worker observability: surface DEAD/failed tasks.** `TaskProcessor` swallows a throwing `TaskHandler`
  into a retryable outcome with no logging, so a task that exhausts its attempts and is marked DEAD is
  invisible to operators. A user who deleted their account gets a 202 but would silently stay tombstoned
  forever if the cleaner failed. Add logging/metrics on handler failure and on DEAD transitions. Exposed by
  profile management's end-to-end test. New 2026-07-22.
- **`findCurrentPasswordHash` tie-breaker.** "Current password" is the latest `user_passwords` row by
  `when_created` (an `@WhenCreated Instant`), with no secondary ordering key. Two hashes written in the same
  clock tick make the current-password determination nondeterministic. Practically unreachable for the human
  register-then-change flow, but a latent fragility on a security-critical read; a monotonic sequence column
  would remove it. New 2026-07-22.
- **Perceptual `ImageHash` (pHash)** for pin deduplication / merging (deliberately YAGNI'd in sub-project 2b).
  Now promoted: it is the flagship of the sequenced **user-segmented base** (see the roadmap section below).

---

## Sequenced roadmap (deliberately ordered, not parked indefinitely)

**Audience / visibility is no longer parked indefinitely** *(re-scoped 2026-07-21; was parked 2026-07-20)*.
It is deliberately **sequenced after a solid user-segmented base**, in this order:

1. **User-segmented base — advanced pin / tag / board management.** Features that make the data model genuinely
   user-segmented and pleasant to use. Flagship: **pin merging via perceptual `ImageHash` / pHash** (see P2).
   Others to be explored when we get there.
2. **Audience mechanics (public / private).** Until this lands everything stays `@Authenticated` and
   owner-scoped (non-owner → 403); no anonymous browsing, no public gallery, no shareable links. It will
   interact with boards (public / shared boards) and with the profile items.

Gated on audience (mechanics to define):

- **Public profiles** — the deferred slice of profile management.
- **Hard-copy of a public pin or board** from user B into user A's own collection: a real, independent copy,
  not a soft link.

Security enrichment (not audience-gated; builds on the profile-management step-up brick):

- **Two-factor / step-up authentication** — TOTP + Passkey/WebAuthn, with a possible short-lived "sudo"
  elevation token for sensitive actions.
