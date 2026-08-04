# Backlog

**Living document.** The priority-ordered list of what is still open. What already shipped lives in git history,
the handoffs under `docs/handoffs/`, and the annotated `vX.Y.Z-*` tags, not here.

Last reviewed: 2026-08-04 (the shared `SqliteConstraintViolations` helper shipped: both collision-translating repositories now route through one object, so that item is removed. Its holistic review found that two of the four unique indexes never translate their violation at all, which is added in its place).

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
- **Unify the ambient-transaction logic.** `EbeanTaskQueue` and `EbeanImageRepository` hand-roll the
  "join the ambient transaction or open my own" check (`transactionControl.currentTransaction() != null`)
  instead of routing through the domain `TransactionRunner.inTransaction { }`. After ADR 0008 they do it
  over `TransactionControl`; consolidating both behind `TransactionRunner` would remove the duplication,
  but it changes Ebean's transaction-nesting behaviour and is its own specification with its own tests.
  Surfaced 2026-08-03 while wiring the ports. **P2**.
- **Soft-delete read-isolation residuals.** ADR 0008 confines the read capability (the `Database`
  instance, the `BeanRepository`/`BeanFinder` supertypes, and the `io.ebean.DB`/`Ebean` static facades)
  so an unfiltered read of a recyclable model is not expressible outside `queries` in any ordinary form.
  Three narrow residuals remain, documented in `docs/specs/2026-07-29-single-representation-soft-delete.md`
  section 4.6 and ADR 0008: a `raw("...")` SQL predicate on `any()`, an in-memory read of `softDeletedAt`
  after a sanctioned query, and a fully-qualified typed reference written without an import (rare). None
  is exercised today; tracked here so the closure's known limits are visible from the backlog. New
  2026-08-03.
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
- **Two unique indexes have no named domain outcome.** The schema carries four unique indexes and
  only two translate their violation: `SqliteConstraintViolations` now serves the exports index
  (`dbmigration/1.11.sql`) and the password-hash one (`1.18.sql`). The other two leak the adapter's
  framework exception outward, which `agents/modules/kotlin.md` forbids ("exceptions cross layers
  only as domain types") and which `docs/adr/0006-domain-owned-timestamps.md:111` already decided
  against for the same shape ("a residual unique-constraint violation is a 409, not a 500"):
  `UserRepository.saveUser` (`UserRepository.kt:54`) lets `jakarta.persistence.PersistenceException`
  escape on a username-case collision, and `UserRepositoryTest.kt:197` pins that leak as the expected
  behaviour, so a race between two sign-ups on the same name is a 500 where the password path
  answers 409. `EbeanTaskQueue.enqueueWithin` (`EbeanTaskQueue.kt:57`) has the same check-then-insert shape
  behind `ux_tasks_dedup` (`1.3.sql`), narrowed but not closed by running in one transaction. The
  root cause is not the two call sites: nothing ties a unique index in the schema to a named
  applicative outcome, so each new index decides again, in silence. The fix is one lot: give both
  indexes a domain error through the shared helper, restate the pinning tests around it, and record
  the tie so the next index inherits it. Surfaced by the holistic review of the
  `SqliteConstraintViolations` extraction, 2026-08-04.
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
