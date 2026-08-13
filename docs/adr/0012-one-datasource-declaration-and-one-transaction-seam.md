# 0012. One datasource declaration, and one transaction seam

Status: Accepted
Date: 2026-08-13

## Context

Two of the three defects closed by `docs/specs/2026-08-13-persistence-p2-debt.md` settle a question
that outlives the fix. The third (three partial indexes on `tasks`) settles a schema tuning
question, is measured in that spec and recorded by its migration, and needs no decision here.

**A datasource declared twice.** `api-persistence-sqlite/src/main/resources/ebean.properties`
declared the SQLite datasource for the benefit of one caller: the repository test suite of its own
module, whose `RepositoryTest` reaches the database through `DB.getDefault()` and therefore through
avaje-config rather than through CDI. Being in `main`, it travelled on the classpath of every
downstream module. In `api-application` it won by arriving first: Ebean bootstrapped a database
named `db` from it before the CDI producer ran, and `EbeanDatabaseProducer`'s builder then returned
that already-registered database instead of the one it had just configured. The producer's
`:memory:` URL was dropped without a warning, and the integration suite wrote its rows to
`api-application/data.db` for as long as this held.

Nothing about that failure is specific to the value that leaked. Any property declared in a
published resource for a test's benefit reaches every consumer of the artefact, and the loser of the
race is silent.

**A transaction seam spelled three times.** `EbeanTaskQueue.enqueue` and `EbeanImageRepository.save`
each carried the same check: if `TransactionControl.currentTransaction()` is not null, join it,
otherwise open one and commit it. `EbeanTransactionRunner.inTransaction`, the domain-facing port
whose whole subject is that boundary, did not carry it: it opened unconditionally. So the rule lived
at the two call sites that happened to need it, and the abstraction meant to own it did not
implement it.

## Decision 1: one declaration per runtime, none that travels

`ebean.properties` is deleted. Each runtime declares its datasource once, in a resource that reaches
only that runtime:

| Runtime | Declaration |
|---|---|
| Production and the Quarkus dev mode | `api-application/src/main/resources/application.properties`, read by `EbeanDatabaseProducer` |
| The `api-application` integration suite | `api-application/src/test/resources/application.properties` |
| The `api-persistence-sqlite` repository suite | `api-persistence-sqlite/src/test/resources/application-test.properties` |

Everything `ebean.properties` declared beyond the URL (ddl generate and run, migration run and path,
entity packages) is already set programmatically by `EbeanDatabaseProducer` for the CDI path, and by
`application-test.properties` for the avaje-config path.

The general form: **a module's `main` resources never carry configuration written for that module's
tests.** A test-only value in a published resource is not a value in the wrong place, it is a value
in every consumer of the artefact.

## Decision 2: `TransactionRunner.inTransaction` is the only seam, and it is REQUIRED

`TransactionControl.beginTransaction()` delegates to `database.beginTransaction(TxScope.required())`,
which Ebean documents as "start a new transaction if there is not a current transaction" and "commit
the transaction if it was created or do nothing if there was already a current transaction"
(`ebean-api-19.2.0-sources.jar`, `io/ebean/Database.java:582`). `EbeanTaskQueue` and
`EbeanImageRepository` then depend on `TransactionRunner` and call `inTransaction { }`, and their
hand-rolled check disappears along with `currentTransaction()`.

An adapter that needs a pair of statements to serialise says so by wrapping them, and says nothing
about who else is holding a transaction. Whether it is the outermost holder stops being its
business, which is the property that made the check duplicable in the first place.

Rejected: hoisting the same `if` into `EbeanTransactionRunner`. It removes the duplication and keeps
a hand-rolled version of semantics the library already names, which is the smaller half of the
problem.

## Consequences

- Nesting two `inTransaction` blocks now commits once, at the outer block. Before, each opened its
  own transaction. No caller nested them, so no behaviour changes today; the guarantee is new.
- `TxScope.requiresNew()` has no user in this codebase. A future one is a deliberate addition to
  `TransactionControl`, not a return to the hand-rolled check.
- The integration suite runs on a fresh in-memory database per run. Two consequences it used to
  hide are gone: state surviving between runs, and two concurrent runs sharing one file. A test that
  silently depended on either now fails, which is the point.
- `api-application/data.db` stops being created. A `.db` file appearing in the tree after a test run
  is once again a signal, and the integration suite pins it
  (`TaskQueueBootIntegrationTest`).
- The repository suite of `api-persistence-sqlite` keeps a declaration Ebean reaches through
  avaje-config, under the one name avaje-config loads as a test overlay
  (`application-test.properties`). That name remains load-bearing and undiscoverable from the code:
  its own file header is where that is written down.
