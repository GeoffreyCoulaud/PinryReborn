# Plan: every unique constraint has a named outcome

Date: 2026-08-04
Specification: `docs/specs/2026-08-04-unique-index-named-outcomes.md` (approved 2026-08-04)
ADR: `docs/adr/0009-unique-index-named-outcomes.md`
Branch: `fix/unique-index-named-outcomes`, off `main` at `f511b15`.

Eight tasks, strictly ordered, dispatched one at a time to a fresh subagent on the single branch. No
worktree: nothing here is parallel work the operator drives, and two agents editing this branch at
once would conflict. Each task is reviewed by a fresh subagent against `agents/reviews/task.md:1-57`
before the next is dispatched.

## Conventions that apply to every task

- **Red first, committed alone.** The failing test is its own commit, `test(scope): <behaviour>`, its
  body carrying the command run and the output it produced, pasted from that run. On this project the
  red is usually a `compileTestKotlin` failure on a type that does not exist yet
  (`agents/project.md`, Conventions).
- **Living docs move with the code.** `agents/project.md` hunks belong in the commit of the change
  they describe, never in a follow-up `docs:` commit. Each task below names its own doc hunk.
- **The gate is per package.** Kover verifies 100% branch coverage grouped by package, so a `catch`
  arm nothing drives fails the build. Where a task cannot drive a branch through a public API, that is
  a signal about where the code belongs, not a reason to lower the bound: the precedent is
  `SqliteConstraintViolations`, which takes an exception factory precisely so its rethrow branch is
  reachable from a test (`docs/handoffs/2026-08-04 - handoff - shared-sqlite-constraint-violations.md`,
  Pitfalls).
- **No `.sql` under `dbmigration/` is modified by any task.** `git status --porcelain` is the check.

---

## T1. Experiment: try to produce both violations

**Goal.** Answer by observation, before any translation is designed, whether the two violations can be
produced in this process, and settle the two facts the dedup design depends on.

**Nature.** A spike. It is not merged as code: TDD's order exemption for throwaway spikes applies
(`AGENTS.md`, Engineering norms). Its product is the ADR section.

**Work.**

1. A concurrency attempt at `UserRepository.saveUser`: N threads registering the same username, on the
   model of `EbeanTaskQueueConcurrencyTest.kt:29-62`.
2. A concurrency attempt at `EbeanTaskQueue.enqueue`: N threads with one dedup key.
3. A deterministic probe for the two facts, which does not need a race:
   - insert a duplicate row directly and observe whether the failure arrives at the `Persistor` call
     or only at `Transaction.commit()`;
   - inside one transaction, catch that failure and then attempt a read, observing whether Ebean left
     the transaction usable.

**Acceptance.**

- `docs/adr/0009-unique-index-named-outcomes.md`, section "Findings from the experiment", is written:
  what was attempted, the commands, their output, and the answer to each of the two facts. It says so
  plainly if nothing reproduced, which is the expected outcome and is the finding.
- No file under `src/main` changed. The spike code is not committed.
- Commit: `docs(adr): record what the unique-constraint experiment produced`.

**Note for the reviewer of T1.** A concurrency attempt that reproduces nothing must not be merged as a
test: it would pass forever regardless of the code, which `AGENTS.md` Evidence calls a check that
cannot fail.

---

## T2. The index-model assertion, red

**Goal.** Assert that every index a migration creates is recorded in Ebean's migration model. It fails
today on four indexes, which is its red.

**Files.** `api-persistence-sqlite/src/test/.../migration/DbMigrationModelCoverageTest.kt`.

**Work.** A third assertion: extract every index name created by a `.sql` under `dbmigration/`, and
assert that each appears as an `indexName` in some `model/*.model.xml`. Express it as the prohibition
(`agents/modules/kotlin.md`, Konsist section): filter to the unrecorded ones, assert the list is empty,
so the failure enumerates them.

**Acceptance.**

- The assertion fails naming exactly `ix_tasks_claim`, `ix_tasks_lease`, `ux_tasks_dedup` and
  `ix_users_name_nocase`.
- Commit `test(persistence): assert every migration index is in the migration model`, its body
  carrying the failing run's output.

---

## T3. Close the model gaps in `1.2` and `1.3`, green

**Goal.** Make Ebean's prior model match the database, so `generateDbMigration` can be trusted on
`tasks` and `users`.

**Files.** `models/TaskModel.kt`, `models/UserModel.kt`, `dbmigration/model/1.3.model.xml`,
`dbmigration/model/1.2.model.xml` (new), `DbMigrationModelCoverageTest.kt` (allowlist),
`agents/project.md`.

**Work.**

1. `TaskModel` declares `ix_tasks_claim`, `ix_tasks_lease` and `ux_tasks_dedup` with
   `@Index(definition = ...)`, the DDL copied verbatim from `1.3.sql:24-27` minus its terminating
   semicolon. `UserModel` declares `ix_users_name_nocase` likewise from `1.2.sql:2`.
2. `1.3.model.xml` gains three `<createIndex>` elements and `1.2.model.xml` is created with one,
   mirroring `1.11.model.xml` and `1.18.model.xml`.
3. `handWritten` in `DbMigrationModelCoverageTest` becomes an empty set.
4. `agents/project.md`, Design invariants: the migration-history line stops counting `1.2` among the
   two accepted costs. This hunk ships in this commit, not later.

**Acceptance.**

- `./gradlew :api-persistence-sqlite:generateDbMigration` reports no change, output shown. This is the
  task's real check: it is what proves the annotations and the XML agree.
- `git status --porcelain` shows no `.sql` under `dbmigration/` modified.
- T2's assertion passes, and the first assertion passes with an empty allowlist.
- `./gradlew :api-persistence-sqlite:test` green.

**Risk.** `MIndex.compare` diffs `indexName`, `tableName`, `columns` and `definition`. Expect to iterate
against the generator rather than to guess the XML. If a form resists after honest attempts, stop and
report: reinstating an exemption is a specification change, not an implementation choice.

---

## T4. The repository translates the username collision

**Goal.** `UserRepository.saveUser` answers a domain exception on a collision under
`ix_users_name_nocase`, and keeps propagating every other failure untouched.

**Files.** `api-domain/.../domain/users/UsernameAlreadyTakenException.kt` (new package),
`repositories/UserRepository.kt`, `api-persistence-sqlite/src/test/.../UserRepositoryTest.kt`.

**Work, in TDD order.**

1. Red: rewrite `UserRepositoryTest.kt:197-205` to expect `UsernameAlreadyTakenException` instead of
   `PersistenceException`, and add two tests against the real store: a name differing only by case
   collides, and a name held by a tombstoned (soft-deleted) account collides. The red is a compile
   failure on the type that does not exist yet.
2. Green: the exception, modelled on `domain/security/PasswordChangeCollisionException.kt` including
   the KDoc that says why it is a domain exception and not a use-case `BaseError`; the `try`/`catch`
   in `saveUser` routing through `SqliteConstraintViolations.translateUniqueConstraint`.

**Acceptance.**

- The three repository tests pass; `UserCreationIntegrationTest` is untouched and green (the pre-check
  still answers first at this point, which is expected).
- No `PersistenceException` other than a unique-constraint one is converted: that branch is already
  held by `SqliteConstraintViolationsTest`, and this task adds no branch of its own.
- Two commits: `test(persistence): ...` then `fix(persistence): ...`.

**Note.** `saveUser` also serves updates (`UserRepositoryTest` has an update case), so a rename onto a
taken name now raises the same exception. That is correct and needs no separate handling; say so in
the KDoc rather than in a comment at the call site.

---

## T5. The use case drops the pre-check

**Goal.** The index becomes the single authority on username uniqueness.

**Files.** `api-usecases/.../UserCreator.kt`, `api-usecases/src/test/.../UserCreatorTest.kt`,
`api-domain/.../repositories/UserRepositoryInterface.kt`, `repositories/UserRepository.kt`,
`api-persistence-sqlite/src/test/.../UserRepositoryTest.kt`, `agents/project.md`.

**Work, in TDD order.**

1. Red: in `UserCreatorTest`, drop every `findUserByNameIncludingDeleted` stub and drive the refusal by
   stubbing `saveUser` to throw `UsernameAlreadyTakenException`, expecting `UsernameAlreadyTakenError`.
   The two tests that today assert the tombstoned and case-variant rules at this level go: those rules
   are now the index's, pinned by T4's repository tests. Removing a test is a deliberate act here, and
   the commit body says which repository test took over.
2. Green: `UserCreator.createUserInternal` loses its read and gains a `try`/`catch` rethrowing
   `UsernameAlreadyTakenError`, the shape of `PasswordChanger.kt:39-43`.
3. `findUserByNameIncludingDeleted` is deleted from `UserRepositoryInterface`, from `UserRepository`,
   and from `UserRepositoryTest`.
4. `agents/project.md`, Design invariants: one line for "the database is the authority on uniqueness",
   scoped to *solely* and naming the export read as the written exception. This hunk ships here.

**Acceptance.**

- `UserCreationIntegrationTest` is untouched and green. This is the criterion that matters: the 409
  contract is now produced by the index and the client cannot tell.
- `rg -n 'findUserByNameIncludingDeleted' --type kotlin` returns nothing.
- `./gradlew gate` green.
- Two commits: `test(usecases): ...` then `refactor(usecases): ...`.

---

## T6. The dedup insert converges

**Goal.** `EbeanTaskQueue.enqueue` returns the live task when its insert loses the dedup race, which is
what `TaskQueueInterface:13-16` already documents.

**Files.** `repositories/EbeanTaskQueue.kt`, `api-persistence-sqlite/src/test/.../EbeanTaskQueueTest.kt`.

**Shape.** Decided by T1. If the transaction survives a caught unique violation, the catch sits in
`enqueueWithin` and re-reads the live task there. If it does not, the catch moves out to `enqueue`,
which retries the read in a fresh transaction, and `enqueueWithin` is unchanged. Read T1's ADR section
before writing anything; do not re-derive the answer.

**Driving the losing path.** The race is not reproducible on a single connection, so the test cannot
create it. Two acceptable routes, in order of preference:

1. Drive the catch through the public API by making the insert fail deterministically, if T1 found a
   way to do so.
2. Otherwise, extract the catch so a test can reach it directly, following
   `SqliteConstraintViolations`: the branch, not the discriminator, is what has to move. The gate's
   per-package bound will refuse an undriven `catch` arm, so this is forced rather than optional.

**Acceptance.**

- A test drives the losing path and asserts the existing task is returned, not an exception.
- `EbeanTaskQueueConcurrencyTest` still green.
- `./gradlew :api-persistence-sqlite:test` green with the coverage bound.
- Two commits: `test(persistence): ...` then `fix(persistence): ...`.

---

## T7. Pin the export refusal precedence

**Goal.** Make explicit the rule that `UserDataExportRequester.kt:58` encodes today by accident of
statement order: when a `PENDING` export exists **and** the minimum interval has not elapsed, the
answer is 409, because the problem is that an export is in progress and not that the request came too
soon.

**Files.** `api-usecases/src/test/.../exports/UserDataExportRequesterTest.kt`.

**Work.** A use-case unit test with a non-zero `minimumInterval`, a stubbed `findPendingForUser`
returning a `PENDING` export and a stubbed `findLastRequestedAtForUser` returning a recent instant,
asserting `ExportAlreadyInProgressError`. Unit level and not integration, because the integration
configuration pins the interval to zero, which is exactly why this case is untested today.

**Acceptance.**

- The test passes against the current code, so its red is a **mutation**: delete
  `UserDataExportRequester.kt:58`, run it, watch it fail with `ExportTooSoonError`, paste that output
  into the commit body, restore the line. This is the project's rule for an assertion that arrives
  green (`agents/project.md`, Conventions).
- Commit: `test(usecases): pin 409 ahead of 429 when an export is already running`.

---

## T8. `UniqueConstraintOutcomeTest`

**Goal.** A new unique constraint cannot enter the schema without someone writing what a client sees
when it fires.

**Files.** `api-persistence-sqlite/src/test/.../migration/UniqueConstraintOutcomeTest.kt` (new),
`agents/project.md`.

**Work.**

1. Extract every unique constraint name from the committed `.sql` files, in both spellings:
   `create unique index <name>` and `constraint <name> unique`.
2. Compare that set to a table declared in the test, one entry per constraint, each carrying its
   outcome. Assert set equality, so a new constraint and a stale entry both fail. The six entries are
   the table in specification section 3; write them naming the real types, which exist by now.
3. `agents/project.md`, Design invariants: one line for "a unique constraint is not complete until its
   outcome is named", pointing at this test. This hunk ships here.

**Acceptance.**

- Its red is a mutation: add a scratch migration carrying a `create unique index`, run the test, watch
  it fail naming that constraint, paste the output into the commit body, delete the scratch file.
- The test's KDoc states its limit: it enforces that an outcome is named, not that it is true.
- `./gradlew gate` green.
- Commit: `test(persistence): require every unique constraint to name its outcome`.

---

## Verify and Wrap

Not tasks, but the phases that follow and what they need.

- **Verify.** `./gradlew gate`, run and its output shown, then a holistic review by a fresh subagent
  over the whole branch diff against `agents/reviews/holistic.md:1-46`. Remember
  `agents/project.md`'s daemon gotcha only if a detekt rule changed, which no task here plans to do.
- **Wrap.** Backlog: delete the item this lot closes, narrow the beta-flattening item to the column
  names, update the `Last reviewed` line. Handoff under `docs/handoffs/`. ADR 0009 `Status: Proposed`
  becomes `Accepted`. PR, rebase merge, after the human review.

## Traceability

| Acceptance criterion (spec section 9) | Task |
|---|---|
| 1. Experiment ran and is recorded | T1 |
| 2. Entities declare their indexes, models record them, generator produces nothing | T3 |
| 3. Index-model assertion, empty allowlist | T2, T3 |
| 4. Repository translates the username collision | T4 |
| 5. Pre-check gone, port method gone | T5 |
| 6. Repository tests for case variant and tombstoned name | T4 |
| 7. `UserCreationIntegrationTest` unchanged and green | T5 |
| 8. Dedup converges | T6 |
| 9. Export refusal precedence pinned | T7 |
| 10. `UniqueConstraintOutcomeTest` | T8 |
| 11. `./gradlew gate` green | Verify |
| 12. `agents/project.md` and the backlog | T3, T5, T8, Wrap |
