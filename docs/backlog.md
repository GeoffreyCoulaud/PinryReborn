# Backlog

**Living document.** The priority-ordered list of what is still open. What already shipped lives in git history,
the handoffs under `docs/handoffs/`, and the annotated `vX.Y.Z-*` tags, not here.

Last reviewed: 2026-07-29.

## How to use this file

- This file holds **open items only**. Do not keep a "shipped" log here: completed work is recorded by git
  history, the handoffs under `docs/handoffs/`, and the annotated `vX.Y.Z-*` tags.
- **Open items** are grouped by priority band, not by module. Each item states the context and what must be
  decided or done, with a pointer to the relevant spec/handoff when one exists.
- `P0` = product decisions that shape the data model and the UI; resolve these before building on top of them.
  `P1` = client ergonomics needed for the web UI and browser extension. `P2` = operational debt already flagged
  in handoffs (not UI blockers).
- When an item is picked up, note the branch/sub-project next to it; when it merges, **delete it from this file**
  (its record now lives in the handoff and the tag).

---

## Open items

### P0: Domain-owned timestamps (specified 2026-07-29, three blocks)

Specified as one piece of work in `docs/specs/2026-07-29-domain-owned-timestamps.md`, with
`docs/adr/0006-domain-owned-timestamps.md`. It absorbs three items that sat in P2 until 2026-07-29
(`softDeletedAt` stamped in the adapter, soft delete not bumping `updatedAt`, and the
`findCurrentPasswordHash` tie-breaker): they were one defect with three faces, a business instant
invented by the persistence adapter rather than stamped by a use case. Scoping them showed the defect
is wider than they recorded, so the work also unifies the two soft-delete mechanisms and deletes
`AuditedBaseModel`.

Delivered as three sequential blocks, one session and one pull request each. The order is imposed by
dependency, not preference.

- **Block 1: uniform soft delete.** Absorbs two of the former items: `softDeletedAt` is stamped inside
  the persistence adapter (`PinRepository.kt:195`, `BoardRepository.kt:50`), and soft delete and
  restore no longer bump `updatedAt`. The block generalises Ebean's `@SoftDelete` to pins and boards
  with the boolean derived by the mapper, turns `User.softDeleted` into `softDeletedAt`, and makes
  account retention read that instant instead of `users.when_modified`, which Ebean rewrites on every
  write to the row. Carries the Konsist assertion banning `Instant.now()` in the persistence layer.
- **Block 2: end of `AuditedBaseModel`.** Not covered by any former item, surfaced while scoping them.
  `tasks.when_modified` drives the deletion of terminal tasks and moves on any row write, the same
  defect as account retention. `Task` receives `terminalStateAt`, `SessionToken` and `HashedPassword`
  receive `createdAt`, seven dead audit columns are dropped, and a Konsist assertion bans
  `@WhenCreated` / `@WhenModified`.
- **Block 3: current-password determinism.** Absorbs the third former item, the
  `findCurrentPasswordHash` tie-breaker. A `(user_id, created_at)` unique constraint plus a
  configurable minimum interval between password changes (default 30 s), with
  `PASSWORD_CHANGE_COLLISION` (409) and `PASSWORD_CHANGED_TOO_SOON` (429).

### P1 — Client ergonomics (needed for the web UI and browser extension)

- **Browser-extension CORS origin.** Deferred from the CORS sub-project (decision B1): the extension
  does not exist yet and has no stable ID, so no origin is wired for it. When it ships, add its
  `chrome-extension://<id>` / `moz-extension://<id>` origin to `api.cors.origins`. See
  `docs/handoffs/2026-07-21 - handoff - cors.md`.
- **User data import (portability).** Export shipped (see `docs/handoffs/2026-07-22 - handoff - user-data-export.md`
  and the `v*-user-data-export` tag); the remaining half is **import**: let a user re-create their pins, boards,
  tags and images on this instance (or another one) from an export archive. The archive format is the contract:
  `docs/specs/2026-07-22-user-data-export.md` §4 defines the layout (`manifest.json`, `pins.jsonl`, `boards.jsonl`,
  `tags.jsonl`, `user.json`, `images/`), `formatVersion` is `1`, and every file's `sha256` is in the manifest.
  Open questions to spec: id remapping vs id preservation, conflict handling with existing rows, image de-dup on
  re-upload, and how much of the archive to trust (signature / manifest verification).

### P2 — Operational debt (flagged in handoffs; not UI blockers)

- **`imageStore.discard` can mask the original error in failure handlers.** `SetPinImage` and
  `DownloadPinImage` call `imageStore.discard(staged)` inside their `catch (e)` rollback blocks; a
  throwing `discard` would mask `e`, the same error-masking that `deleteQuietly` now prevents for
  `imageStore.delete`. No `discardQuietly` extension exists yet. Extend `StorageCleanup` when a
  non-delete cleanup needs best-effort. New 2026-07-27.
- **Task worker observability: surface DEAD/failed tasks.** `TaskProcessor` swallows a throwing `TaskHandler`
  into a retryable outcome with no logging, so a task that exhausts its attempts and is marked DEAD is
  invisible to operators. A user who deleted their account gets a 202 but would silently stay tombstoned
  forever if the cleaner failed. Add logging/metrics on handler failure and on DEAD transitions. Exposed by
  profile management's end-to-end test. New 2026-07-22.
- **Authentication attempt limiting (brute force).** `PasswordChanger` verifies the current password
  before changing it, and `POST /api/v1/sessions` verifies it to issue a token: both are password
  oracles, and neither limits attempts. The minimum interval added by P0 block 3 counts **successful**
  changes only (it reads the current hash's `createdAt`), so a failed attempt writes nothing and costs
  the caller nothing. Do not read "there is a rate limit on password change" as "brute force is
  handled". Limiting attempts needs state the codebase does not have (a per-user failure counter, its
  expiry, its behaviour across instances), so it is its own specification. Surfaced while specifying
  the P0 lot, 2026-07-29.
- **Flatten the migration history at beta.** The project is alpha (see docs/project.md): breaking changes and data loss
  are
  acceptable, nobody should be running it yet. The migration history is nonetheless append-only, and that already
  constrains fixes: `1.2` is a hand-written case-insensitive unique index that `@Index(definition = ...)` would
  express today (`DbMigrationModelCoverageTest` lists it), and `users`/`pins`/`boards`/`tags` keep `when_created` /
  `when_modified` column names that no longer match the domain's `createdAt` / `updatedAt`, both kept only because
  rewriting an applied migration changes its checksum and breaks startup. At beta, collapse `1.0` to `1.n` into a
  single generated baseline and take both fixes with it. Until then, when a fix is blocked only by an already-applied
  migration, prefer the clean design and record the debt here rather than contorting around it. New 2026-07-23.
- **Periodic maintenance via the task queue instead of dedicated schedulers.** The worker runs three
  periodic lifecycles (task poll, export retention purge, garbage collection), each on its own
  single-thread scheduler. The task queue is a solid, retried, state-tracked system, and periodic work
  could plausibly be modelled as recurring tasks it dispatches rather than as separate schedulers.
  Surfaced while wiring the schedulers by type (2026-07-27). Trade-off to spec: today's design isolates
  the garbage collection sweep's heavy filesystem and database work on its own thread so it cannot
  starve the worker pool serving user tasks (downloads, exports, account deletes), and a sweep is an
  idempotent loop with no client waiting on a result, which the terminal-state `tasks` model does not
  fit naturally. Routing maintenance through the task queue would unify retry and observability (a
  failing sweep would surface as a DEAD task instead of an error log), but it needs a recurrence
  mechanism the queue does not have, and either a dedicated worker pool or acceptance that sweeps
  compete with user tasks. The poll lifecycle itself cannot disappear: SQLite has no push, so the queue
  needs a poller regardless. New 2026-07-27.

### Features

- **Perceptual `ImageHash` (pHash)** for pin deduplication / merging. Flagship of the sequenced **user-segmented base
  ** (see the roadmap section below).
- **Advanced pin / tag / board management** : Features that make the data model genuinely user-segmented and pleasant to
  use. To be explored.
- **Import from 3rd party sites**
  Initial candidates :
    - Pinterest (board import),
    - Danbooru / Gelbooru / Other booru (favorites import),
    - Instagram (saved collection import),
    - Reddit / Twitter / Pixiv (saved posts import).

  On some of these sites, a post may contain multiple media. We're not changing our semantic 1 pin = 1 media rule.
  Can be either a one-time import, or to sync a local pinry board with a remote source periodically, as the user
  chooses.
- **Video support** : Completes the 3rd party use case, since those allow posting videos as well. Videos are a 1st class
  citizen, just like images. Their renditions are the video's thumbnail in case of a still rendition, or an animated
  image of the first few seconds of the video (eg. 3s)
- **RBAC and quota system** : Allow admins to toggle features and define quotas per-role, from the API
- **Audience mechanics (public / private).** Until this lands everything stays `@Authenticated` and owner-scoped (
  non-owner → 403); no anonymous browsing, no public gallery, no shareable links. It will interact with boards (public /
  shared boards) and with the profile items.
- **Two-factor / step-up authentication** — TOTP + Passkey/WebAuthn, with a possible short-lived "sudo" elevation token
  for sensitive actions.

Gated on audience mechanics :

- **Hard-copy of a public pin or board** from user B into user A's own collection: a real, independent copy, not a soft
  link.
- **Public profiles** — the deferred slice of profile management.
