# Backlog

**Living document.** The priority-ordered list of what is still open. What already shipped lives in git history,
the handoffs under `docs/handoffs/`, and the annotated `vX.Y.Z-*` tags, not here.

Last reviewed: 2026-07-30 (blocks 1 and 2 of the domain-owned timestamps work delivered and removed; block 3 remains).

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

### P0: Domain-owned timestamps (specified 2026-07-29, one block left of three)

Specified as one piece of work in `docs/specs/2026-07-29-domain-owned-timestamps.md`, with
`docs/adr/0006-domain-owned-timestamps.md`. It absorbed three items that sat in P2 until 2026-07-29
(`softDeletedAt` stamped in the adapter, soft delete not bumping `updatedAt`, and the
`findCurrentPasswordHash` tie-breaker): they were one defect with three faces, a business instant
invented by the persistence adapter rather than stamped by a use case. Scoping them showed the defect
is wider than they recorded, so the work also unifies the two soft-delete mechanisms and deletes
`AuditedBaseModel`.

Three sequential blocks were planned, one session and one pull request each, in an order imposed by
dependency rather than preference. **Blocks 1 and 2 have shipped** (2026-07-29 and 2026-07-30) and
closed all three absorbed items except the password-hash determinism block 3 carries. Records: block 1
in `docs/handoffs/2026-07-29 - handoff - single-representation-soft-delete.md`,
`docs/specs/2026-07-29-single-representation-soft-delete.md` and
`docs/adr/0007-single-representation-soft-delete.md`; block 2 in
`docs/handoffs/2026-07-30 - handoff - end-of-audited-base-model.md` and
`docs/specs/2026-07-29-end-of-audited-base-model.md`. One remains:

- **Block 3: current-password determinism.** Absorbs the third former item, the
  `findCurrentPasswordHash` tie-breaker. A `(user_id, created_at)` unique constraint plus a
  configurable minimum interval between password changes (default 30 s), with
  `PASSWORD_CHANGE_COLLISION` (409) and `PASSWORD_CHANGED_TOO_SOON` (429). Block 2 already added
  `HashedPassword.createdAt` (domain-stamped from `Clock`) and ordered `findCurrentPasswordHash` on
  it, so block 3 adds only the constraint, the interval, and the two error codes.

### P1: Client ergonomics (needed for the web UI and browser extension)

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

### P2: Operational debt (flagged in handoffs; not UI blockers)

- **detekt runs without type resolution, and the tasks that have it are red.** `check` depends on
  `:detekt`, so `detektMain` and `detektTest` are run by neither the gate nor CI. Measured 2026-07-29:
  `detektMain` fails in four of the twelve modules, `api-presentation-quarkus` 16 findings,
  `api-persistence-sqlite` 11, `api-usecases` 5, `api-application` 1, the other eight clean. All of it
  predates the work that found it. The decision needed is either type-resolution detekt in the gate
  with those 33 findings cleared, or a recorded choice to run detekt without it. Being neither is the
  defect: a task that is red and never run says nothing about the code and everything about the setup.
  New 2026-07-29.
- **Inverse associations on the persistence models.** The module maps twelve entities and not one
  `@OneToMany` or `@ManyToMany` among them, so a question about "the boards of a pin" or "the pins of a
  board" can only be asked from the join table. Two consequences: the soft-delete work needs two
  extension functions on `QPinBoardModel` that would otherwise be plain `PinQueries` / `BoardQueries`
  calls, and `savePinTags` / `savePinBoards` (`PinRepository.kt:83-147`) synchronise join rows by hand,
  reading, diffing and deleting, which is what a mapped collection does for you. **The cycle is not the
  obstacle**, contrary to what `PinBoardModel`'s KDoc suggests: Kotlin compiles type cycles inside a
  module and the project already has one between `models` and `models.bases`
  (`AuthoredBaseModel` imports `UserModel`, which extends `BaseModel`). What has to be proven first is
  Ebean's behaviour on the paths this project uses: `ModelRepository.saveAndReturn` is `merge` on a
  detached bean, and a detached bean carrying an empty or uninitialised collection is ambiguous
  (no change, or empty the collection), which every pin save would go through; and whether adding an
  association flips `BeanDescriptor.isDeleteByStatement`, which decides how delete queries compile
  (see `docs/adr/0007-single-representation-soft-delete.md`, fact 3). Its own lot, with its own tests.
  New 2026-07-29.

- **`ModelRepository` inherits Ebean finders that no soft-delete guard can see.** It extends
  `io.ebean.BeanRepository`, so each of the seven repositories holding one also holds the public
  `findAll()`, `findById(id)`, `findByIdOrEmpty(id)` and `db()` inherited from `BeanFinder`
  (`ebean-api/src/main/java/io/ebean/BeanFinder.java:87-116` at tag `v19.0.0`, the nearest tag to the
  pinned 19.2.0). Each roots a read on the entity class, or hands out the `Database` that can, while
  naming no query bean and writing no `softDeletedAt` predicate: the two Konsist assertions and the
  `SoftDeleteStateFilteredOutsideQueries` rule both key on those two shapes, so a recycled row read
  through one comes back with nothing raised. Nothing calls them today, the field being used for
  `saveAndReturn` only at all seven sites, so this is an open door rather than a defect. Closing it
  means `ModelRepository` holding a `Database` instead of extending `BeanRepository` and exposing
  `saveAndReturn` alone, plus a Konsist assertion barring `BeanRepository` and `BeanFinder` as
  production supertypes. The cost is small and bounded: one class rewritten around `Database.merge`,
  which is what `BeanRepository.merge` already delegates to (`BeanRepository.java:200-202`, same tag),
  and its seven callers untouched. It does not close the wider hole, since anything holding a
  `Database` can still root an unfiltered query and no rule sees that either. **P2**: the surface is
  unused, so it costs nothing today and the first call through it is what would make it urgent. New
  2026-07-29.
- **A session token can outlive the tombstone that should have killed it, by one insert.** What
  stops a tombstoned account from being used is **revocation, not filtering**: `AccountDeleter`
  marks the user and revokes every session in one transaction, while the read path asks nothing
  about the account's state. `SessionTokenAuthenticator` resolves a token through
  `findByTokenHash`, which roots on `session_tokens` alone, and `SessionTokenModelMapper`
  dereferences the `user` association, a load that names no query bean and writes no
  `softDeletedAt` predicate, so neither the Konsist assertions nor the detekt rule can see it.
  The window: a session insert whose own active-user check passed just before the tombstone
  committed, and which lands just after `deleteAllForUser` has run, produces a token nothing
  revoked. **Defence in depth, not an exploitable hole**: the window is one insert wide, it needs
  the account's own credentials, and the account deletion task deletes that user's tokens seconds
  later. Closing it means the read path filtering too, cheapest as an extension on
  `QSessionTokenModel` declared beside the query constructors (the shape `withActivePin` and
  `withActiveBoard` already use on `QPinBoardModel`) so `findByTokenHash` returns nothing for a
  tombstoned owner; the alternative, resolving the owner and inserting the token in one
  transaction, closes the race at the write end instead and costs every authenticated request
  nothing. Surfaced reviewing the single-representation soft delete, 2026-07-29.

- **Four `!!` in the soft-delete transitions of the pin and board repositories.**
  `PinRepository.softDeletePin` and `restorePin` (`PinRepository.kt:196,204`) and
  `BoardRepository.softDeleteBoard` and `restoreBoard` (`BoardRepository.kt:50,58`) each end on
  `findOne()!!`. `agents/modules/kotlin.md` forbids `!!` outright: a value that cannot be null is
  modelled non-nullable, one that can is handled, and this one can. The row is fetched by the id of
  an entity the use case has just read, so the window is a concurrent hard delete, and the assertion
  turns it into a `NullPointerException` out of the adapter instead of a domain error. The single
  representation rewrote all four lines and kept the `!!` deliberately: what to do with an absent row
  is a behaviour decision nobody has taken (return quietly, as `markPendingDeletion` and
  `permanentlyDeleteUser` do, or throw a domain error the presentation layer maps), and taking it
  there would have been fixing what was not asked. Inventoried here instead, as that branch's plan
  said it would be. New 2026-07-29.

- **One tombstoned owner stops the export retention sweep for every other user.**
  `ReapExpiredUserDataExports.reap` re-saves each expired `READY` export with no per-item guard, and
  `UserDataExportRepository.save` throws `UserModelDoesNotExistError` for a tombstoned owner, so one
  such export aborts the batch: it loses its archive bytes and keeps its `READY` state, and every
  export behind it is left unswept. Pre-existing, not a regression: `@SoftDelete` hid the same row
  from the same lookup. Settle first whether a tombstoned owner's exports should be swept at all or
  left to the account deletion task, since that decides whether the fix is a per-item guard or a
  narrower query. Surfaced reviewing the single-representation soft delete, 2026-07-29.

- **Test sources read the wall clock freely.** Production code takes every instant from the `Clock`
  port and the `WallClockRead` rule holds it there, but `detekt.yml` excludes `**/test/**` and
  `**/testFixtures/**` from that rule, so tests call `Instant.now()` and its three siblings at will:
  a grep over the test sources counts 266 reads across 54 files. That figure is a rough upper bound
  rather than the rule's own count, since some of those lines are code snippets inside string
  literals in the rule's own tests, which no syntax tree ever sees; measuring it by activating the
  rule against test sources is the first step of the work. Closing it means the test sources taking
  a fixed clock, most plausibly carried by the shared test bases. Its own lot rather than part of
  the soft-delete work: the files it touches have nothing to do with recycling. New 2026-07-29.

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
- **Two-factor / step-up authentication**: TOTP + Passkey/WebAuthn, with a possible short-lived "sudo" elevation token
  for sensitive actions.

Gated on audience mechanics :

- **Hard-copy of a public pin or board** from user B into user A's own collection: a real, independent copy, not a soft
  link.
- **Public profiles**: the deferred slice of profile management.
