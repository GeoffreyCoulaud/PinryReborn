# Backlog

**Living document.** The priority-ordered list of what is still open. What already shipped lives in git history,
the handoffs under `docs/handoffs/`, and the annotated `vX.Y.Z-*` tags, not here.

Last reviewed: 2026-08-02 (the operational-debt wave delivered its nine in-scope items; the resolved entries are removed, the four the wave surfaced are added).

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

- **Inverse associations on the persistence models.** The module maps twelve entities and not one
  `@OneToMany` or `@ManyToMany` among them, so a question about "the boards of a pin" or "the pins of
  a board" can only be asked from the join table. Two consequences: the soft-delete work needs two
  extension functions on `QPinBoardModel` that would otherwise be plain `PinQueries` / `BoardQueries`
  calls, and `savePinTags` / `saveBoards` (`PinRepository.kt:82,113`) synchronise join rows by hand,
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
- **Shared `SqliteConstraintViolations` helper.** T3 narrowed `UserDataExportRepository.save`'s catch
  by extracting `isUniqueConstraint` / `translateIfCollision` into its companion, mirroring the same
  pair already on `UserPasswordHashRepository`. The two pairs are now duplicated across two
  repositories. Extract one shared helper (the cause structure `PersistenceException(SQLiteException)`
  and the `resultCode == SQLITE_CONSTRAINT_UNIQUE` discriminator are identical) and have both sites
  call it. Small, mechanical; deferred to avoid widening T3's diff. New 2026-08-02.
- **`PinRepositoryTest` split decision.** T4 tipped `PinRepositoryTest` over `LargeClass`; it is held
  by a reasoned `@Suppress("LargeClass")` (it is the comprehensive main suite; feature slices are
  sibling classes). Keep the suppress, or split the suite along its feature slices so the suppress can
  go. A judgement call, not a defect. New 2026-08-02.

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
