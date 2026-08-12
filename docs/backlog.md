# Backlog

**Living document.** What is still open, banded by nature first and by priority second. What already shipped lives
in git history, the handoffs under `docs/handoffs/`, and the annotated `vX.Y.Z-*` tags, not here.

Last reviewed: 2026-08-12 (the P2 triage lot, `docs/specs/2026-08-12-p2-debt-triage.md`. Sixteen P2 items disposed
of: eight fixed, two closed by a decision, two moved to Before beta, one to Known limits, three left as open work.
The lot's own reviews then filed two new ones, so the band ends at five. The bands are new, and so is the rule that
fills them, `docs/adr/0010-review-finding-dispositions.md`).

## How to use this file

- This file holds **open items only**. Do not keep a "shipped" log here: completed work is recorded by git
  history, the handoffs under `docs/handoffs/`, and the annotated `vX.Y.Z-*` tags.
- **Three bands, by nature.** *Open work* is what someone will do. *Known limits* points at the document that
  records each one and holds no copy of it. *Before beta* holds dated events no session can start early. A limit
  is not debt and is not counted as debt.
- **Open work is grouped by priority**, not by module. Each item states the context and what must be decided or
  done, with a pointer to the relevant spec or handoff when one exists. `P0` = product decisions that shape the
  data model and the UI. `P1` = client ergonomics needed for the web UI and the browser extension. `P2` =
  operational debt (not UI blockers).
- **A review finding has four exits and only one is this file**: fixed inside the lot, a backlog item, an
  accepted limit written where the decision lives, or refused with the reason in the handoff
  (`docs/adr/0010-review-finding-dispositions.md`). Wrap states which exit each finding took.
- When an item is picked up, note the branch or sub-project next to it; when it merges, **delete it from this
  file** (its record now lives in the handoff and the tag).

---

## Open work

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
  Surfaced 2026-08-03 while wiring the ports.
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
  needs a poller regardless. New 2026-07-27, kept as its own session by the 2026-08-12 triage.
- **The three partial indexes on `tasks` serve none of the queries they were created for.** Both halves
  are measured. SQLite does not use a partial index when the value its predicate tests arrives as a
  bound parameter (sqlite 3.53.3, against the committed definitions): with `state = ?` the claim query
  plans as `SCAN tasks` plus `USE TEMP B-TREE FOR ORDER BY`, and with `state = 'PENDING'` written as a
  literal it plans as `SCAN tasks USING INDEX ix_tasks_claim`; the dedup lookup is `SCAN` against
  `SEARCH ... USING INDEX ux_tasks_dedup` the same way. And Ebean binds: `Query.getGeneratedSql()` on
  the two queries as the adapter builds them returns `where t0.state = ? and t0.available_at <= ?` and
  `where t0.dedup_key = ? and t0.state in (?,?)`. So `ix_tasks_claim` and `ix_tasks_lease`
  (`1.3.sql:23,25`) cost writes and buy nothing, `ux_tasks_dedup` still enforces uniqueness but does not
  speed its own lookup, and `claimNext` scans the whole table on every poll. The fix is a decision
  between inlining the state literal, dropping the partial predicate from the two non-unique indexes,
  and leaving it. Surfaced by the T3 review of the 2026-08-12 triage; the Ebean half was measured before
  that lot wrapped.
- **The api-application integration tests run against a file, not memory.**
  `api-application/src/test/resources/application.properties:5` declares
  `datasource.db.url=jdbc:sqlite::memory:`, and `api-application/data.db` is nonetheless written during
  a test run (observed 2026-08-12, mtime tracking the run). Two other declarations of the same key
  exist, `api-persistence-sqlite/src/main/resources/ebean.properties:26` and
  `api-application/src/main/resources/application.properties:12`, both naming `data.db`, so the
  question is which one wins and why. Consequences worth checking before this is dismissed: state
  survives between runs, two concurrent runs share one file, and a locally mutated migration stays
  applied in `db_migration` after the `.sql` is deleted. **This is a candidate cause for the
  `SQLITE_BUSY` that the 2026-08-12 triage closed as unreproduced.** Surfaced by the T6 review and
  confirmed independently by the T6 task review, 2026-08-12.

## Known limits

Recorded where the decision lives. None is a copy: follow the pointer.

- **Soft-delete read isolation leaves residuals.**
  `docs/adr/0008-structural-soft-delete-read-isolation.md`, and
  `docs/specs/2026-07-29-single-representation-soft-delete.md` section 4.6.
- **A unique constraint's named outcome is not checked against what the code does.**
  `docs/adr/0009-unique-index-named-outcomes.md`, decision 1.
- **The partial-index state guard has a declared reach**, and one part of it is a correctness gap
  rather than a documentation one: it pins the predicate, not the uniqueness columns that make
  `findOne()` return at most one row. `PartialUniqueIndexStates` and
  `api-persistence-sqlite/src/test/kotlin/.../migration/PartialUniqueIndexStatesTest.kt`.

## Before beta

Dated events. No session starts these early.

- **Authentication attempt limiting (brute force).** `PasswordChanger` verifies the current password
  before changing it, and `POST /api/v1/sessions` verifies it to issue a token: both are password
  oracles, and neither limits attempts. The minimum interval added by P0 block 3 counts **successful**
  changes only (it reads the current hash's `createdAt`), so a failed attempt writes nothing and costs
  the caller nothing. Do not read "there is a rate limit on password change" as "brute force is
  handled". Limiting attempts needs state the codebase does not have (a per-user failure counter, its
  expiry, its behaviour across instances), so it is its own specification. Surfaced while specifying
  the P0 lot, 2026-07-29. **Before beta, and it is the one security gap that is deliberately open**:
  the project is alpha and nobody should be running it.
- **Flatten the migration history.** The migration history is append-only, and that already constrains
  fixes: `users`/`pins`/`boards`/`tags` keep `when_created` / `when_modified` column names that no
  longer match the domain's `createdAt` / `updatedAt`, kept only because rewriting an applied migration
  changes its checksum and breaks startup. At beta, collapse `1.0` to `1.n` into a single generated
  baseline and take that fix with it. Until then, when a fix is blocked only by an already-applied
  migration, prefer the clean design and record the debt here. New 2026-07-23.

## Features

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
