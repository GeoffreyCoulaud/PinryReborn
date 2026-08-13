# Handoff: the persistence P2 lot

Date: 2026-08-13
Branch: `refactor/persistence-p2-debt`
Spec: `docs/specs/2026-08-13-persistence-p2-debt.md`
ADR: `docs/adr/0012-one-datasource-declaration-and-one-transaction-seam.md`
Plan: `docs/plans/2026-08-13-persistence-p2-debt.md`

Three P2 items taken as one lot, chosen by the operator for their shared module and nothing else.
All three are closed. Seven commits, each revertable alone.

## Current state

| Item | Outcome |
|---|---|
| The integration suite ran on a file | Runs in memory. `ebean.properties` deleted, both properties files completed, two tests pin it. |
| The ambient-transaction check was duplicated | Both adapters route through `TransactionRunner.inTransaction { }`. The check is gone, not moved. |
| Three partial indexes served nothing | `ix_tasks_claim` recreated non-partial and used; `ix_tasks_lease` dropped; `ux_tasks_dedup` untouched. |

`./gradlew gate` green. No `.db` file is created anywhere by a full run.

## What was built, and the two things that were not what they looked like

**The test database was worse than reported.** The backlog said a stray `data.db` was written. In
fact the whole integration suite ran on it: rows written by the application landed in that file, and
the `:memory:` URL the CDI producer was given was discarded in silence. `ebean.properties` sat in
`main` resources, so Ebean bootstrapped a database named `db` from it before the producer ran, and
the producer's builder returned that existing database instead of the one it had just configured.

**The transaction check never did anything.** The spec, the ADR and the plan all asserted that
nesting two `inTransaction` blocks opened two transactions, and that the adapters' hand-rolled check
was therefore load-bearing. The plan review asked for the measurement, and it refuted the claim:
Ebean's no-arg `beginTransaction()` already carries REQUIRED semantics
(`io/ebean/Database.java:475`), so nesting was already flat and the check was reproducing by hand
what the call underneath did. Group B was requalified from a fix to a behaviour-preserving refactor,
and the three documents were corrected before the code changed.

## Pitfalls, in the order someone will meet them

- **The default `Database` has two creation paths and the race decides which object serves the
  application.** `EbeanDatabaseProducer` configures one in code; avaje-config builds another from a
  properties file. The worker's `StartupEvent` observer reaches a query bean, and so
  `DB.getDefault()`, before anything asks the producer, so avaje-config wins and the producer's
  configuration is discarded. Every properties file must therefore declare the full set, and
  `ProductionDatasourceDeclarationTest` pins the production one. This is the lot's one open item.
- **`jdbc:sqlite::memory:` needs the pool pinned to one connection.** Each connection otherwise gets
  its own private database, and the connection that ran the migrations is not the one a query lands
  on. It surfaces as `no such table: tasks` during boot, which reads like a migration failure and is
  not one.
- **`api-application`'s test `application.properties` is not an avaje-config overlay.** It shares
  the production file's name and wins by classpath order alone. Its neighbour in
  `api-persistence-sqlite` uses `application-test.properties`, which is a real overlay name that
  avaje-config looks for. The two files solve the same problem by different mechanisms; each says so
  in its header.
- **A partial index is skipped when its predicate tests a bound parameter**, and Ebean binds. This
  is what made three indexes useless. It generalises past this table.
- **Measure the plan, do not reason about it.** The backlog recorded `SCAN tasks` for the claim
  query; it actually used the composite index `1.15` added, and paid a temporary sort rather than a
  scan. The same re-measurement is what turned dropping `ix_tasks_lease` from an argument into a
  fact: `reapExpired` never used it.

## What is not validated

- **The `%prod` profile was never started.** That production reads its properties through
  avaje-config is inferred from the code path plus the measurement taken in the test runtime, which
  runs the same wiring. It is not an observation of a production boot. The test that pins the
  declaration reads the file, not a running configuration.
- **`SQLITE_BUSY`, closed as unreproduced on 2026-08-12, is not settled.** This lot removed a
  candidate cause (two concurrent runs sharing one file). It did not reproduce or refute the
  symptom.
- **The claim index is measured on an empty table.** `EXPLAIN QUERY PLAN` reports the plan the
  planner picks, which is what was in question; no timing was taken, and none is meaningful at this
  size.

## Next step

The PR, then the operator's review. After it merges, `Improve` runs from `main` on its own branch,
carrying one retained remedy already identified: the rule that a partial index whose predicate tests
a bound parameter is not used by SQLite belongs in `agents/engineering.md`, and did not ship here
because this lot does not declare that rule as its subject.
