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
3. A deterministic probe of **fact 1**, where the violation surfaces. Four combinations, because the
   two write paths this lot touches differ on both axes and the answer may not be uniform:
   `Persistor.merge` (which is what `UserRepository.saveUser` uses, through
   `ModelRepository.kt:9`) and `Persistor.save` (which is what `EbeanTaskQueue` uses), each in
   autocommit and inside an explicit transaction. Production always writes inside one:
   `UserCreator.kt:21,27` wraps every creation in `transactionRunner.inTransaction`, while
   `RepositoryTest` gives a repository no ambient transaction, so the two differ and the tests written
   later run in the weaker mode.
4. A deterministic probe of **fact 2**, whether the transaction survives: inside one transaction,
   catch the violation and then attempt a read, observing whether Ebean left the transaction usable.
   Only T6 consumes this, and only for `save`, so probe it there; answering it for `merge` as well is
   cheap and worth doing, but it is `save` that gates a task.

**Acceptance.**

- `docs/adr/0009-unique-index-named-outcomes.md`, section "Findings from the experiment", is written:
  what was attempted, the commands, their output, and the answer to each fact, fact 1 answered per
  combination. It says so plainly if nothing reproduced, which is the expected outcome for the two
  concurrency attempts and is itself the finding.
- `git status --porcelain` is empty. The spike leaves nothing behind, tracked or untracked: a stray
  test file under `src/test` would pollute every later task's run.
- Commit: `docs(adr): record what the unique-constraint experiment produced`.

**Two stop clauses hang on fact 1**, one per operation, because the answer may differ between them:

- **`merge` inside a transaction fails only at commit**: stop before T4 is dispatched. The catch this
  lot puts in `saveUser` would never fire in production, and no test written in T4 could see it,
  because `RepositoryTest` autocommits.
- **`save` inside a transaction fails only at commit**: stop before T6 is dispatched, for the same
  reason at the other site.

Either is a specification change, not an implementation choice. The transactional answer is the one
that binds: production writes inside a transaction at both sites.

**Note for the reviewer of T1.** A concurrency attempt that reproduces nothing must not be merged as a
test: it would pass forever regardless of the code, which `AGENTS.md` Evidence calls a check that
cannot fail.

---

## T2. The index-model assertion, red

**Goal.** Assert that every index a migration creates is recorded in Ebean's migration model. It fails
today on four indexes, which is its red.

**Files.** `api-persistence-sqlite/src/test/.../migration/DbMigrationModelCoverageTest.kt`.

**Work.** A third assertion: extract every index name created by a `.sql` under `dbmigration/`, and
assert that each is recorded by a `createIndex` element in some `model/*.model.xml`, the element rather
than the `indexName` attribute, which a `dropIndex` carries too. Express it as the prohibition
(`agents/modules/kotlin.md`, Konsist section): filter to the unrecorded ones, assert the list is empty,
so the failure enumerates them.

**Guard the extraction.** An assertion that filters a set down and ends on empty passes just as well
when the filter matched nothing (`agents/project.md`, Conventions). Assert the extracted index set is
non-empty, the way `DbMigrationModelCoverageTest.kt:48-53` already guards the directory listing for the
same reason. T2's red proves the extraction matches today; nothing else keeps it matching tomorrow.

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

**Why "no change" is the wrong observable on its own.** The generator already reports no change on the
unmodified tree, measured on 2026-08-04 at `cecc331`:

```
$ ./gradlew :api-persistence-sqlite:generateDbMigration --rerun-tasks
> Task :api-persistence-sqlite:generateDbMigration
DbMigration> no changes detected - no migration written
BUILD SUCCESSFUL in 12s
$ git status --porcelain
$ ls api-persistence-sqlite/src/main/resources/dbmigration/*.sql | wc -l
18
```

The `DbMigration>` line is the one that carries the claim; `BUILD SUCCESSFUL` prints just as happily
when a migration *was* written. So "the generator produced nothing" is equally true of having done the
work, having done half of it, and having done none. The task is therefore built in two steps with a
**visible change in between**.

**Work.**

1. **Step A, the red.** Declare the four indexes on their entities and nothing else, each as
   `@Index(name = "...", definition = "...")` with no `columnNames` and no `unique`: all four are
   partial or expression indexes whose DDL only the `definition` attribute can carry, so they take the
   `UserPasswordHashModel.kt:16-20` form rather than the `UserDataExportModel.kt:17-23` one. `TaskModel`
   gets `ix_tasks_claim` (`1.3.sql:23`), `ix_tasks_lease` (`1.3.sql:25`) and `ux_tasks_dedup`
   (`1.3.sql:27`); `UserModel` gets `ix_users_name_nocase` (`1.2.sql:2`). Copy each DDL verbatim from
   the line named, without its terminating semicolon; the lines between them are comments, not DDL.
   Run the generator. It now writes a migration carrying four `create index` statements for indexes
   that already exist in every deployed database. Paste that output into the commit body: it is the
   evidence that the prior model was wrong.
2. **Step B, the green.** The generated `<version>.model.xml` from step A holds the four
   `<createIndex>` elements Ebean itself derived from those annotations, with the four attributes
   `MIndex.compare` diffs already filled. **Harvest them**: move three into `1.3.model.xml` and one
   into a new `1.2.model.xml`, changing nothing but the containing file. Do not hand-write them from
   the `1.11.model.xml` / `1.18.model.xml` precedents, which disagree with each other because they came
   from different annotation forms. Then delete the generated `.sql` and `.model.xml` pair and re-run
   the generator: it writes nothing, and now that silence means something.
3. `handWritten` in `DbMigrationModelCoverageTest` becomes an empty set.
4. `agents/project.md`, Design invariants: the migration-history line stops counting `1.2` among the
   two accepted costs. This hunk ships in this commit, not later.

**The generated migration is live while it sits on disk.** `ebean.properties:8` sets
`ebean.migration.run=true`, so the module's test bootstrap applies whatever `.sql` is in
`dbmigration/`, and these `create index` statements carry no `if not exists`: any test run in that
window dies with "index ix_tasks_claim already exists". Run nothing but the generator between steps A
and B, and delete the generated pair before any other command. If a test did run in the window, delete
the test database files too: `.gitignore:5-7` ignores `*.db`, `*.db-shm` and `*.db-wal`, so a database
left carrying a phantom migration row is invisible to every `git status` check in this plan.

**One commit, not two.** The Conventions block above has the failing artefact commit alone, and that is
right for a test. It is wrong here: a step-A-only commit leaves four indexes declared on entities and
recorded in no model file, which is the exact drift T3 exists to remove, and **no assertion catches
it**, because all three of `DbMigrationModelCoverageTest`'s checks key on `.sql` files and step A
changes none. The red is a pasted generator run, not a committed artefact.

**Acceptance.**

- Both generator runs are in the commit body: the one that emitted four `create index` statements, and
  the one that emitted nothing.
- `git status --porcelain` shows nothing under `dbmigration/` changed except `model/1.3.model.xml` and
  the new `model/1.2.model.xml`. Scoping this to `.sql` alone would let a stray generated model file
  through, and a detected drop lands in a `pendingDrops` change set in a model file rather than in
  apply output (`agents/project.md`, Commands).
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

1. Red: `UserRepositoryTest.kt:197-205` is already the case-variant test
   (`saving two users whose names differ only by case is rejected`), so rewrite it to expect
   `UsernameAlreadyTakenException` instead of `PersistenceException`, and **add one** test: a name
   held by a tombstoned (soft-deleted) account collides. Two behaviours in total, which is what
   specification criterion 6 asks for. The red is a compile failure on the type that does not exist
   yet.
2. Green: the exception, modelled on `domain/security/PasswordChangeCollisionException.kt` including
   the KDoc that says why it is a domain exception and not a use-case `BaseError`; the `try`/`catch`
   in `saveUser` routing through `SqliteConstraintViolations.translateUniqueConstraint`.

**Acceptance.**

- The two repository tests pass; `UserCreationIntegrationTest` is untouched and green (the pre-check
  still answers first at this point, which is expected).
- No `PersistenceException` other than a unique-constraint one is converted: that branch is already
  held by `SqliteConstraintViolationsTest`, and this task adds no branch of its own.
- Two commits: `test(persistence): ...` then `fix(persistence): ...`.

**Read T1's finding for `merge` before writing the catch.** These tests run through `RepositoryTest`,
which gives the repository no ambient transaction, while production always writes inside one
(`UserCreator.kt:21,27`). If T1 found the violation surfaces only at commit under `merge` in a
transaction, this task's catch is dead code in production and its tests cannot show it: stop and
report instead of writing it.

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
   and from its two call sites in `UserRepositoryTest`: the dedicated test at `:157-165`, which goes,
   and the last assertion of `Given a tombstoned user, Then normal lookups hide it but
   including-deleted finds it` at `:70`, which loses one assertion while its subject (tombstone
   visibility) survives.
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

**Files.** `repositories/EbeanTaskQueue.kt`, `repositories/SqliteConstraintViolations.kt`,
`api-persistence-sqlite/src/test/.../EbeanTaskQueueTest.kt`,
`api-persistence-sqlite/src/test/.../repositories/SqliteConstraintViolationsTest.kt`.

**The shared helper is widened, deliberately.** Converging needs to tell a unique violation from every
other `PersistenceException` and then **return a value**. The only discriminator in the repository is
`SqliteConstraintViolations.isUniqueConstraint`, which is `private` (`SqliteConstraintViolations.kt:34`),
and its public member returns `Nothing` (`:25`), so it can only throw. Duplicating the discriminator
would undo the extraction that shipped three commits ago; widening the object is the alternative and it
is in scope for this task.

Generalise it to one recovery function, keeping the existing entry point as a special case:

```kotlin
fun <T> onUniqueConstraint(error: PersistenceException, recover: (PersistenceException) -> T): T
fun translateUniqueConstraint(error, toDomainError): Nothing = onUniqueConstraint(error) { throw toDomainError(it) }
```

This keeps one discriminator and **one** rethrow branch, both still owned by
`SqliteConstraintViolations` and its test. That matters beyond tidiness: it is what lets T4 say it adds
no branch of its own, and inlining the check in `EbeanTaskQueue` would give T6 a third branch, the
non-unique rethrow, that the plan does not budget for. Update `SqliteConstraintViolationsTest`'s KDoc,
which argues today that the rethrow branch lives in the object because no caller can reach it: the
argument survives the generalisation but its wording names one function.

**Shape.** Read T1's ADR section before writing anything; do not re-derive the answer.

- **If fact 1 says a `save` inside a transaction only fails at commit, stop and report.** That is the
  mode `enqueueWithin` always runs in, since `enqueue` opens its own transaction whenever there is no
  ambient one (`EbeanTaskQueue.kt:50`). The catch would never fire, the decorator below could not
  produce a catchable violation, and on the ambient branch the failure would surface inside
  `UserDataExportRequester`'s commit instead. Same gate as T4's, for the other operation.
- **If the transaction survives a caught unique violation**, the catch sits in `enqueueWithin` and
  re-reads the live task there. This shape works on both of `enqueue`'s branches
  (`EbeanTaskQueue.kt:46-55`) because it needs no transaction of its own.
- **If it does not survive, stop and report.** The obvious fallback, moving the catch out to `enqueue`
  to retry in a fresh transaction, cannot be built: on the ambient branch (`:47-48`) `enqueue` owns no
  transaction, and that branch is live in production
  (`UserDataExportRequester.kt:47` wraps `createPending`, which enqueues at `:68-72` and writes again
  at `:73`). Repairing the caller's poisoned transaction from inside `enqueue` would break its
  atomicity. The candidate answer worth discussing is then asymmetric, converging when `enqueue` owns
  the transaction and rethrowing when it joined one, but a documented method behaving differently on
  its two call paths is a specification decision and not the implementer's.

**Driving the losing path.** The race is not reproducible while `enqueue` holds its check and its
insert in one transaction, which is what serialises the pair (ADR 0009, findings), so a test cannot
create it. It can create the *situation* the race produces, through `enqueue`'s own public surface,
with a `Persistor` decorator around the real one:

1. **The convergent case.** On its first `save` of a `TaskModel`, the decorator writes a conflicting
   live task carrying the same dedup key through the real persistor, then delegates the original save,
   which now violates `ux_tasks_dedup`. The production path runs whole: the check misses, the insert
   violates, the catch re-reads, and the conflicting task is what `enqueue` must return. Run it on both
   of `enqueue`'s branches, ambient and own: there is no autocommit mode, since `enqueue` opens a
   transaction when it finds none. Assert on the returned `Task`, whose id is the decorator's row. An
   assertion on stored rows would have to commit the ambient branch itself, since `enqueue` does not.
2. **The case where the re-read finds nothing**, reachable with a decorator that raises the violation
   without writing anything. This is a branch, so the coverage bound will demand it, and it needs a
   decided answer: rethrow the original violation. The documented contract is "returns that existing
   task", and there is no existing task, so there is nothing to converge on and a 500 is honest.
   Record that in the KDoc.

Extracting the catch into a helper testable in isolation is **not** an acceptable substitute. It would
satisfy a criterion worded "a test drives the losing path" while leaving nothing that pins
`enqueueWithin` to calling it, so an `enqueue` that never converges would still pass. The
`SqliteConstraintViolations` precedent is narrower than it looks: it moved the *rethrow* branch alone,
and the translation itself stayed observable through the repository's public save.

**Acceptance.**

- A test drives the losing path **through `enqueue`** and asserts the existing task is returned, not
  an exception, on both the ambient and the own-transaction branch.
- A test drives the empty-re-read case and asserts the violation propagates.
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

**Work.** A use-case unit test stubbing `findPendingForUser` to return a `PENDING` export and asserting
`ExportAlreadyInProgressError`. Unit level and not integration, because the integration configuration
pins the interval to zero, which is exactly why this case is untested today. The fixture's
`minimumInterval` is already `Duration.ofHours(1)` (`UserDataExportRequesterTest.kt:47`), so no new
instance is needed: the interval is non-zero and the export's `requestedAt` sits inside it.

**Do not stub `findLastRequestedAtForUser`.** `BaseTest.kt:16-21` runs MockK's `checkUnnecessaryStub()`
after every test, and `UserDataExportRequester.kt:58` throws before reaching that read, so stubbing it
would fail the green run on the stub check rather than passing. The other stubs the path does reach are
still needed: `reauthenticator.reauthenticate`, `transactionRunner.inTransaction`, `clock.now` and
`findPendingForUser`. The sibling test at `UserDataExportRequesterTest.kt:127-139` shows the set.

**Acceptance.**

- The test passes against the current code, so its red is a **mutation**, and the mutation is a stub
  swap rather than an addition: delete `UserDataExportRequester.kt:58`, remove the now-unreachable
  `findPendingForUser` stub, add the `findLastRequestedAtForUser` one the mutated path needs, run it,
  watch it fail with `ExportTooSoonError`. Paste that output into the commit body, then restore all
  three. Skipping the stub swap reddens on MockK instead ("no answer found" on the added read, or
  `checkUnnecessaryStub` on the orphaned one), which proves nothing about the precedence: say in the
  commit body which failure is the evidence if the pasted run carries more than one.
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
  it fail naming that constraint, paste **that test's** output into the commit body, delete the scratch
  file before any other run.
- Expected collateral from that mutation, so it is not mistaken for the red: by now `handWritten` is
  empty and T2's assertion has no exemption, so the scratch file also reddens both of
  `DbMigrationModelCoverageTest`'s assertions, and `ebean.properties:8` sets `ebean.migration.run=true`,
  so the module's test bootstrap will try to apply it. Only `UniqueConstraintOutcomeTest`'s failure is
  the evidence.
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
