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
  constrains fixes: `users`/`pins`/`boards`/`tags` keep `when_created` / `when_modified` column names that no longer
  match the domain's `createdAt` / `updatedAt`, kept only because rewriting an applied migration changes its checksum
  and breaks startup. At beta, collapse `1.0` to `1.n` into a single generated baseline and take that fix with it.
  Until then, when a fix is blocked only by an already-applied migration, prefer the clean design and record the debt
  here rather than contorting around it. New 2026-07-23. **Narrowed 2026-08-05**: this item used to carry a second
  debt, the hand-written `1.2` index, which is now closed. It was never blocked by the checksum: `1.2.sql` is
  untouched, and what was missing was a `.model.xml`, which is generator state rather than applied DDL
  (`docs/adr/0009-unique-index-named-outcomes.md`, decision 5).
- **No repository test pins the cause chain on a translated constraint violation.** All three
  collision-translating sites pass the original `PersistenceException` into their domain exception, and
  all three tests assert the type alone (`UserRepositoryTest.kt:204,215`,
  `UserPasswordHashRepositoryTest.kt:86`, `UserDataExportRepositoryTest.kt:263`). Replace
  `SomeException(cause = it)` with `SomeException()` at any of the three and every test stays green.
  `SqliteConstraintViolationsTest` pins that the helper hands the failure to the factory, not that the
  caller wires it through. The cost is a lost stack trace in a log, since the use case translates to
  its status code either way, which is why this is one item over three sites rather than a fix on the
  one written most recently. Surfaced by the T4 review, 2026-08-05. **P2**, and small.
  **Widened by the T5 review**: the same gap has a shape as well as a test side. Every one of these
  error types declares `cause: Throwable? = null`, so dropping the chain at a new call site compiles
  and passes. `UsernameAlreadyTakenError` has one construction site today and its exception's KDoc
  already anticipates a second (a rename use case). A required `cause` would make the drop a compile
  error. Left as it stands for now because the optional shape is the convention every sibling follows
  (`PasswordChangeError.kt:16`), so changing one is worse than changing none: decide it across the set.
- **The tombstoned-name refusal exists only as a composition of two levels.** Since the pre-check
  went, "a name held by an account pending deletion is still taken" is pinned by a repository test
  (the index refuses the row) and by a use-case test (the translation to 409), with nothing joining
  them: no integration test drives that scenario end to end, unlike the case-variant one
  (`UserCreationIntegrationTest.kt:191-208`). The composition is sound, because the catch is
  type-based and scenario-blind, so this is a coverage seam rather than a defect. Surfaced by the T5
  review, 2026-08-05. **P2**.
- **`UserCreator.createUser(name)` has no production caller.** `UserController` calls
  `createUserWithPassword`, so the password-less entry point is exercised only by its unit test.
  Predates this branch (`e102883`). Either it is a public surface someone intends to use, or it is
  code nobody asked for and goes. Surfaced by the T5 review, 2026-08-05. **P2**, and small.
- **A partial index's `where` clause and the Kotlin query that mirrors it agree only by hand.**
  `ux_tasks_dedup` is partial on `dedup_key is not null and state in ('PENDING','RUNNING')`
  (`dbmigration/1.3.sql:27`), and `EbeanTaskQueue.findLiveTaskWithDedupKey` repeats that state set in
  Kotlin. The two agreeing is what makes the dedup fast path correct and the recovery's empty re-read
  unreachable, and nothing ties them: a migration narrowing or widening the index would leave the query
  silently disagreeing. `UniqueConstraintOutcomeTest` names each index's outcome, not its predicate.
  Same shape for `uq_user_data_exports_pending` and `findPendingForUser`. Surfaced by the T6 review and
  its remediation, 2026-08-05. **P2**.
- **A test run left a 515 MB JVM heap dump at the repository root, and a forced gate run failed once
  with `SQLITE_BUSY, the database file is locked`.** Both on 2026-08-05, minutes apart. The gate then
  passed three consecutive forced runs with no such message, and the dump was deleted, so neither is
  reproduced and neither is tied to a change. A heap dump means a test JVM died on an
  `OutOfMemoryError`, and a JVM dying mid-run is a plausible source of the lock, so the two are
  probably one event rather than two. Nothing in the build sets `-XX:+HeapDumpOnOutOfMemoryError` and
  no module sets a heap bound. Worth a look before it is met in CI, where it reads as a flake. `.hprof`
  is deliberately **not** gitignored: hiding the artefact would hide the signal. New 2026-08-05. **P2**.
- **Nothing checks that a migration model's index matches the DDL that was applied.**
  `DbMigrationModelCoverageTest` compares index *names* between the `.sql` files and the
  `model/*.model.xml`, and `generateDbMigration` compares the model against the entity annotations the
  model was harvested from, so the two agree by construction. A `<createIndex>` whose `definition`
  differs from the `create index` line its migration actually ran would pass both. Today's nine are
  byte-exact, checked by hand during the T3 review of 2026-08-05, which is what makes this a gap in the
  net rather than a defect. The assertion to add pairs each `<createIndex definition>` with the
  create-index line of the `.sql` of the same version. New 2026-08-05.
- **`UserDataExportModel.kt:16` states a rule about Ebean that is not true**, and it is the only
  precedent a reader finds for a partial index. Its comment says `columnNames` "keeps the index in the
  migration model so a later diff drops and recreates it correctly"; Ebean keys an index by name and
  compares `tableName`, `unique`, `definition` and the column lists between the two model sides, so a
  `definition`-only declaration diffs correctly on its own. The codebase now carries both forms, and
  the wrong comment is attached to the older one. `agents/project.md` Gotchas states the right form;
  correct or delete the comment. Surfaced by the T3 review, 2026-08-05. **P2**, and small.
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
- **Two unique indexes have no named outcome, and an implicit invariant is what closes them.** The
  schema carries four unique indexes and only two translate their violation: `SqliteConstraintViolations`
  serves the exports index (`dbmigration/1.11.sql`) and the password-hash one (`1.18.sql`).
  `UserRepository.saveUser` (`UserRepository.kt:54`) lets `jakarta.persistence.PersistenceException`
  escape on a username-case collision (`1.2.sql`), and `UserRepositoryTest.kt:197` pins that leak as
  the expected behaviour; `EbeanTaskQueue.enqueueWithin` (`EbeanTaskQueue.kt:57`) inserts under
  `ux_tasks_dedup` (`1.3.sql`) with no catch. Both leak a framework exception across a layer, which
  `agents/modules/kotlin.md` forbids.
  **The two want different outcomes.** Only the username collision wants an error:
  `UserCreator.createUserInternal` (`UserCreator.kt:43`) already answers `UsernameAlreadyTakenError`
  (409) from its pre-check, and a lost race should be indistinguishable from it. The dedup insert
  wants convergence instead, because `TaskQueueInterface.enqueue` (`TaskQueueInterface.kt:13-16`)
  documents that a live dedup key returns the existing task without inserting.
  **Neither violation is reachable in-process today, which is the real finding.** Both need two
  callers to interleave a read and a write, and the datasource is pinned to a single connection
  (`EbeanDatabaseProducer.kt:53-54`, rationale in `ebean.properties:16-23`), which serialises them.
  The password-hash index differs because it collides by value: two hashes at the same instant
  collide however well serialised, which is what `docs/adr/0006-domain-owned-timestamps.md:111`
  describes. So the exposure is not a 500 today, it is a 500 the day the single-connection decision
  changes, with nothing to say so. The lot therefore starts by trying to produce the violation at
  both sites rather than by designing against it. Root cause: no rule ties a unique index to a named
  applicative outcome, and the invariant that closes these two is implicit. Surfaced by the holistic
  review of the `SqliteConstraintViolations` extraction, corrected against the connection-pool
  configuration, 2026-08-04.
- **The export refusal precedence is pinned only at unit level.** `UserDataExportRequesterTest`
  asserts that a live `PENDING` export answers 409 rather than 429, but MockK's
  `checkUnnecessaryStub` forces that assertion into the shape
  `verify(exactly = 0) { repository.findLastRequestedAtForUser(any()) }`: an internal call count, not
  the outcome a client sees. An integration companion asserting `409 EXPORT_ALREADY_IN_PROGRESS`
  rather than `429 EXPORT_TOO_SOON` would pin the wire behaviour the ADR argues for
  (`docs/adr/0009-unique-index-named-outcomes.md`, decision 2). It is feasible: the shared export
  profile pins the interval to zero (`MeExportTestProfile.kt:20`), which is why no existing export
  integration test can reach this case, and `MePasswordRateLimitIntegrationTest.kt:14` is the in-repo
  precedent for a per-class profile that overrides one interval. Not done inside T7 because the task's
  file list was the use-case test alone: the companion is a new `@QuarkusTest` class with its own
  profile and its own boot in `api-application`, which is scope T7 did not carry. Surfaced by the T7
  review, 2026-08-05. **P2**.
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
