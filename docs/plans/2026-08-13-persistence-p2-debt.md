# Plan: three persistence defects

Date: 2026-08-13
Spec: `docs/specs/2026-08-13-persistence-p2-debt.md`
ADR: `docs/adr/0012-one-datasource-declaration-and-one-transaction-seam.md`
Branch: `refactor/persistence-p2-debt`

Six tasks in three pairs, red then green. The pairs are independent of each other except for the
order: pair A first, because until it lands no test result from `api-application` is trustworthy.

Each task ends with `./gradlew gate` unless it says otherwise. Every task is reviewed on completion
by a fresh subagent against `agents/reviews/task.md`, and no implementer reviews its own task.

---

## Pair A: the integration suite runs in memory

### A1. Red: pin the integration datasource to memory

**Files.** `api-application/src/test/kotlin/.../TaskQueueBootIntegrationTest.kt`.

**What.** Add one case asserting the running database has no backing file. `pragma_database_list`
returns one row per attached database with a `file` column that is empty for an in-memory database
and holds the absolute path otherwise. Read it through `DB.getDefault()` (as
`SessionAuthIntegrationTest:127` already does; the `database` member of `IntegrationTest` is
private, and this task does not widen it).

Chosen this suite because it is already the runtime-wide boot test, and a new `@QuarkusTest` class
costs a full boot (`agents/engineering.md`, test conventions).

**Acceptance.**
- `./gradlew :api-application:test --tests "TaskQueueBootIntegrationTest"` fails, on the new case
  only, reporting the `data.db` path it found.
- The failure output is pasted into the commit body.
- Commit alone: `test(persistence): pin the integration datasource to memory`.

### A2. Green: delete `ebean.properties`

**Files.** Delete `api-persistence-sqlite/src/main/resources/ebean.properties`.

**What.** Nothing else, first. Then run the gate and read what falls.

**Acceptance.**
- A1's case passes.
- `./gradlew gate` is green.
- After a full run, `find . -name '*.db' -o -name '*.db-wal' -o -name '*.db-shm'` prints nothing
  (run from a tree where `api-application/data.db` was deleted first, so the check can fail).
- `./gradlew :api-persistence-sqlite:test` still green on its own, proving the repository suite kept
  its own declaration.
- Any test that fails only because it inherited state from a previous run is fixed here if the
  defect is in the test, and reported as a finding (not fixed) if it is in the product. The final
  message names each one either way.

**Risk to watch.** Ebean may now bootstrap through a different path than before (CDI producer first
rather than avaje-config first). The evidence that the right one won is A1's assertion, not the
absence of an error.

---

## Pair B: one transaction seam

### B1. Red: the seam's guarantees

**Files.** `api-persistence-sqlite/src/test/kotlin/.../EbeanTransactionRunnerTest.kt`, plus the task
queue and image repository repository-level tests.

**What.** Three cases, each failing against today's implementation:
1. Nesting is flat: `inTransaction { inTransaction { write } }` and a rollback of the outer block
   leaves no row.
2. `TaskQueueInterface.enqueue` called inside an `inTransaction { }` that rolls back leaves no task.
3. `ImageRepositoryInterface.save` called inside an `inTransaction { }` that rolls back leaves no
   image row.

Cases 2 and 3 pass today by accident (both adapters already join an ambient transaction); if one
does pass, it is kept as a regression guard and the commit body says so rather than inventing a
failure.

**Acceptance.**
- At least case 1 fails, output pasted into the commit body.
- Commit alone: `test(persistence): pin the transaction seam's nesting and rollback`.

### B2. Green: `TxScope.required()` and one caller

**Files.** `TransactionControl.kt`, `EbeanTransactionControl.kt`, `EbeanTransactionRunner.kt`,
`EbeanTaskQueue.kt`, `EbeanImageRepository.kt`, `EbeanTransactionControlTest.kt`.

**What.**
- `EbeanTransactionControl.beginTransaction()` becomes
  `database.beginTransaction(TxScope.required())`.
- `EbeanTaskQueue` and `EbeanImageRepository` take `TransactionRunner` instead of
  `TransactionControl`; their `if` and its four-line comment go; `claimNext`'s `return@use` becomes
  a return from the `inTransaction` block and its explicit commits go.
- `TransactionControl.currentTransaction()` is deleted, along with its test, if nothing calls it.
- The KDoc of `TransactionControl` and the class KDoc of `EbeanTaskQueue` (which describes the old
  seam, `EbeanTaskQueue.kt:28-31`) are updated in the same commit (living-document simultaneity).

**Acceptance.**
- B1's three cases pass.
- `grep -rn "currentTransaction" --include="*.kt" api-*/src/main` prints nothing.
- `./gradlew gate` green, coverage bound included (deleting a branch may leave a test asserting a
  path that no longer exists; delete the test, never lower the bound).

---

## Pair C: the indexes on `tasks`

### C1. Measure the claim query as it is built today

**Files.** None. A throwaway script under the scratchpad, and its output.

**What.** Get the SQL Ebean actually emits for `claimNext` (`Query.getGeneratedSql()`), then run
`EXPLAIN QUERY PLAN` on it against a database migrated to the current head, with the parameters
bound. Record both plans: the claim query and `reapExpired`.

**Acceptance.**
- The `SCAN tasks` plus `USE TEMP B-TREE FOR ORDER BY` plan is reproduced and pasted, so the after
  measurement has a before to sit against. If it does not reproduce, stop and report: the premise of
  pair C is then wrong.

### C2. Change the indexes and prove the plan changed

**Files.** `TaskModel.kt`, a new generated migration under
`api-persistence-sqlite/src/main/resources/dbmigration/`, `SweepIndexesMigrationTest.kt` if it
names either index, `agents/engineering.md`.

**What.**
- `TaskModel`: `ix_tasks_claim` becomes a plain `@Index(columnNames = [...])` if the generator emits
  the column order this needs (`state`, then `priority desc`, `available_at asc`, `id asc`);
  otherwise it keeps a `definition` without the `where` clause. Which one shipped is stated in the
  commit body.
- `ix_tasks_lease` is removed from `TaskModel`.
- `./gradlew :api-persistence-sqlite:generateDbMigration`, then read the generated `.sql` before
  trusting it. Precedent for a generated index drop: `1.15.sql`. If the generator emits the drops as
  pending, take the `pendingDropsFor` route and commit both files together (precedent: `1.13` and
  `1.14__dropsFor_1.13`).
- The migration carries a one-line comment saying why `ix_tasks_lease` is not replaced: `RUNNING`
  rows are bounded by `worker_count`, and `ix_tasks_state_terminal_state_at` covers the `state`
  prefix.
- `agents/engineering.md`, Persistence section: one line recording the rule this lot establishes,
  that a partial index whose predicate tests a bound parameter is not used by SQLite. It ships in
  this commit under the simultaneity exception (`agents/writing.md`), not as a separate `docs:`.

**Acceptance.**
- C1's measurement re-run reports `SEARCH tasks USING INDEX ix_tasks_claim` and no
  `USE TEMP B-TREE FOR ORDER BY`. Before and after pasted in the commit body.
- `1.3.sql` is byte-identical to its committed version (`git diff --stat` shows it untouched).
- `DbMigrationModelCoverageTest`, `PartialUniqueIndexStatesTest` and `SweepIndexesMigrationTest`
  green.
- `./gradlew gate` green.

---

## Wrap

Runs after Verify (full gate, then a holistic review of the whole branch diff by a fresh subagent).

- `docs/backlog.md`: delete the three closed items, update `Last reviewed`.
- `docs/handoffs/2026-08-13 - handoff - persistence-p2-debt.md`.
- PR, rebase merge only after the operator's review has come back.
- `api-application/data.db` and the scratchpad copy are gone from the working tree; the file is
  gitignored, so `git status --porcelain` would not have shown it.
