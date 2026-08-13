# Plan: three persistence defects

Date: 2026-08-13
Spec: `docs/specs/2026-08-13-persistence-p2-debt.md`
ADR: `docs/adr/0012-one-datasource-declaration-and-one-transaction-seam.md`
Branch: `refactor/persistence-p2-debt`
Revised: 2026-08-13, after the plan review. What changed is listed at the bottom.

Five tasks in three groups. The groups are independent of each other except for the order: group A
first, because until it lands no test result from `api-application` is trustworthy.

Every task ends with `./gradlew gate`, except the red ones (A1), which run only the single test
named in their acceptance and whose commit is red by construction.

---

## Group A: the integration suite runs in memory

### A1. Red: pin the integration datasource to memory

**Files.** `api-application/src/test/kotlin/.../TaskQueueBootIntegrationTest.kt`.

**What.** One case that ties the writer to the handle it reads, in this order:

1. Write a row through an injected port (`taskQueue`, already injected at line 25).
2. Read it back through `DB.getDefault()`.
3. Assert `pragma_database_list` on that same handle reports an empty `file` column.

Asserting step 3 alone is what the review rejected: after A2 the avaje-config path also resolves to
`:memory:`, so the handle would report in-memory even if the CDI producer had built a separate
file-backed instance and the application were writing there. Steps 1 and 2 are what make the handle
the application's handle.

`IntegrationTest.database` is private and this task does not widen it; the test reads
`DB.getDefault()` directly, as `SessionAuthIntegrationTest:127` does. This suite is chosen because
it is already the runtime-wide boot test and a new `@QuarkusTest` class costs a full boot
(`agents/engineering.md`, test conventions).

**Acceptance.**
- `./gradlew :api-application:test --tests "TaskQueueBootIntegrationTest"` fails on the new case
  only, reporting the `data.db` path it found.
- The failure output is pasted into the commit body.
- Commit alone: `test(persistence): pin the integration datasource to memory`.

### A2. Green: delete `ebean.properties`

**Files.** Delete `api-persistence-sqlite/src/main/resources/ebean.properties`.

**What.** Nothing else, first. Then run the gate and read what falls.

**Acceptance.**
- A1's case passes.
- `./gradlew gate` is green.
- After a full run, `find . -name '*.db' -o -name '*.db-wal' -o -name '*.db-shm'` prints nothing,
  run from a tree where `api-application/data.db` was deleted first so the check can fail.
- `./gradlew :api-persistence-sqlite:test` green on its own, proving the repository suite kept its
  own declaration.
- Any test failing only because it inherited state from a previous run is fixed here if the defect
  is in the test, and reported as a finding (not fixed) if it is in the product. The final message
  names each one either way.

**Risk to watch.** Ebean may now bootstrap through a different path than before (CDI producer first
rather than avaje-config first). The evidence that the right one won is A1's assertion, not the
absence of an error.

---

## Group B: one transaction seam

Not red-then-green. The measurement in the spec (section 2.2) shows the semantics are already
REQUIRED, so removing the check preserves behaviour. TDD's ordering exemption applies; its safety
net does not lift. That net is in place before the change:

- `EbeanTransactionRunnerTest`, "Given a write in a nested inTransaction, Then a rollback of the
  outer block discards it", committed in `62defd5`.
- `EbeanTransactionRunnerTest`, "Given a rolled-back transaction, Then neither the task nor the
  download row exists" (pre-existing, `:64`), which already covers `enqueue` inside a rolled-back
  `inTransaction`.
- `EbeanTaskQueueConcurrencyTest:29`, which guards `claimNext`'s select-then-update under 8 threads.

### B1. Guard the envelope the refactor could delete

**Files.** `api-persistence-sqlite/src/test/kotlin/.../repositories/EbeanTaskQueueTest.kt`, and the
image repository test for the rollback case the spec's criterion 2 leaves open.

**What.** Two cases:

1. With no ambient transaction, `enqueue` holds a transaction open across its dedup check and its
   insert. A `Persistor` fake asserts `currentTransaction() != null` at the moment the insert
   reaches it. Without this, an implementer who deletes the envelope along with the `if` gets a
   green gate, and the invariant `agents/engineering.md` names for `EbeanTaskQueue.enqueue` breaks
   silently. `claimNext`'s half needs nothing new.
2. `ImageRepository.save` inside a rolled-back `inTransaction { }` leaves no image row (spec
   criterion 2's second half; the `enqueue` half already exists).

**Acceptance.**
- Both pass against today's code (they are guards, not reds); the commit body says so rather than
  inventing a failure.
- Commit alone: `test(persistence): guard the task queue's transactional envelope`.

### B2. Route both adapters through `TransactionRunner`

**Files.** `EbeanTaskQueue.kt`, `EbeanImageRepository.kt`, and every construction site of the two:
`EbeanTaskQueueTest.kt:27,105,120,142,156`, `EbeanTaskQueueConcurrencyTest.kt:26`,
`EbeanImageRepositoryTest.kt:19`, `EbeanTransactionRunnerTest.kt:27,29`. `RepositoryTest.kt:22`
exposes `transactionControl` and no `transactionRunner`: add the accessor there rather than
repeating `EbeanTransactionRunner(transactionControl)` at eight sites.
`EbeanTaskQueueTest.kt:120` opens its ambient transaction through `transactionControl` and keeps
doing so: that is the port for opening one, and it stays.

**What.**
- Both adapters take `TransactionRunner` instead of `TransactionControl`; the `if` and its four-line
  comment go; `claimNext`'s three explicit commits (`EbeanTaskQueue.kt:117,131,140`) and its two
  `return@use` (`:118,:132`) become a plain `inTransaction { }` block with returns from it.
- No `TxScope` is introduced (ADR 0012, decision 2, second rejection).
- `TransactionControl.currentTransaction()` stays, for B1's fake. Its KDoc says why it has no
  production caller.
- The KDoc of `TransactionControl` and the class KDoc of `EbeanTaskQueue:28-31`, which both describe
  the old seam, are corrected in this commit (living-document simultaneity).

**Acceptance.**
- `grep -rn "currentTransaction" api-*/src/main --include="*.kt"` returns only the declaration in
  `TransactionControl.kt` and its implementation in `EbeanTransactionControl.kt`.
- The three pre-existing guards listed above, plus B1's two, all still pass.
- `./gradlew gate` green, coverage bound included. Deleting a branch may strand a test asserting a
  path that no longer exists: delete that test, never lower the bound.

---

## Group C: the indexes on `tasks`

### C1. Measure both queries as they are built today

**Files.** None. A throwaway script under the scratchpad, and its output.

**What.** Take the SQL Ebean emits for `claimNext` and for `reapExpired`
(`Query.getGeneratedSql()`), then run `EXPLAIN QUERY PLAN` on each against a database migrated to
the current head, parameters bound as Ebean binds them.

**Acceptance.**
- The claim query reproduces `SCAN tasks` plus `USE TEMP B-TREE FOR ORDER BY`, pasted. If it does
  not reproduce, stop and report: the premise of group C is then wrong.
- `reapExpired`'s plan is recorded too, because C2 removes the index it may be using.

### C2. Change the indexes, prove both plans

**Files.** `TaskModel.kt`, a new generated migration pair under
`api-persistence-sqlite/src/main/resources/dbmigration/`, `SweepIndexesMigrationTest.kt`.

**What.**
- `ix_tasks_claim`: keep the `definition` form, minus the `where` clause, with `state` moved to the
  front. Preferred over `columnNames` because the direction tokens carry the result: SQLite serves a
  mixed `ORDER BY priority desc, available_at asc, id asc` from an index only if the index carries
  those directions, and an all-ascending rendering would fail C2's own plan assertion in a way that
  looks like a planner mystery. `columnNames` is used only if the generated SQL is read and proves
  it keeps the directions.
- `ix_tasks_lease`: removed from `TaskModel`.
- `./gradlew :api-persistence-sqlite:generateDbMigration`, then read the generated `.sql` before
  trusting it. The generator writes a numbered pair (`1.19.sql` plus `model/1.19.model.xml`, `1.18`
  being the head). **If the output is wrong, delete that pair before regenerating**, or the next run
  stacks `1.20` on top of a wrong `1.19`.
- The drop and the create must land in the same file. The `pendingDropsFor` route is refused here:
  `MigrationDirectory.versionKeyOf` sorts `1.20__dropsFor_1.19` after `1.19`, so a same-name
  recreation would be created and then dropped by the replayed history. That route is for column
  drops (precedent `1.13`/`1.14`), not for an index recreated under its own name.
- `SweepIndexesMigrationTest` gains a case pinning `ix_tasks_claim` in `currentIndexes` (the
  replayed-history reading, `agents/engineering.md`, test conventions). Without it, nothing durable
  holds the index this lot creates: the plan measurement is a throwaway script.
- The migration carries a one-line comment on why `ix_tasks_lease` is not replaced.

**Acceptance.**
- Claim query: `SEARCH tasks USING INDEX ix_tasks_claim`, no `USE TEMP B-TREE FOR ORDER BY`. Before
  and after pasted in the commit body.
- `reapExpired`: its plan after the drop is pasted next to C1's. If it degrades to a full `SCAN` on
  a table whose `RUNNING` rows are bounded by `worker_count`, that is accepted and said out loud in
  the commit body; if it degrades in a way that is not bounded, stop and report.
- `1.3.sql` untouched (`git diff --stat` shows it absent).
- `DbMigrationModelCoverageTest`, `PartialUniqueIndexStatesTest`, `SweepIndexesMigrationTest` green.
- `./gradlew gate` green.

---

## Wrap

Runs after Verify (full gate, then a holistic review of the whole branch diff by a fresh subagent).

- `docs/backlog.md`: delete the three closed items, update `Last reviewed`.
- `docs/handoffs/2026-08-13 - handoff - persistence-p2-debt.md`.
- Improve, on its own branch from `main`: the rule that a partial index whose predicate tests a
  bound parameter is not used by SQLite. It belongs in `agents/engineering.md` and not in this lot,
  which does not declare it as its subject (`agents/writing.md`), and it predates this lot anyway
  (measured by the T3 review of the 2026-08-12 triage).
- PR, rebase merge only after the operator's review has come back.
- `api-application/data.db` and the scratchpad copy gone from the working tree. The file is
  gitignored, so `git status --porcelain` would not have shown it.

---

## What the plan review changed

Its two CRITICAL findings, both accepted:

1. Group B's red was asserted, never measured, and the repository contradicted it. Measured before
   revising: nesting is already flat, so the group is a behaviour-preserving refactor. The spec and
   ADR 0012 carried the same false claim and were corrected with it.
2. Nothing in the plan would have noticed if the refactor deleted `enqueue`'s transactional envelope
   along with the redundant check. B1 now guards it, and `currentTransaction()` stays as its
   observation point instead of being deleted.

Five MAJOR and five MINOR findings, also accepted: the missing construction sites in B2, A1's
assertion not tying writer to handle, the `agents/engineering.md` edit routed to Improve,
`reapExpired` measured before and after rather than before only, a durable guard for
`ix_tasks_claim`, `definition` preferred over `columnNames`, the regeneration recovery step, the
`pendingDropsFor` ordering trap, the pre-existing coverage of B1's rollback case, the three commits
in `claimNext` rather than one, and the gate-runs-everywhere preamble that contradicted the red
tasks.
