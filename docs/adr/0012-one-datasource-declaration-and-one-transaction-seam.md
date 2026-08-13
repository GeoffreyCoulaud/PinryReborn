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

**A transaction seam spelled twice, over a library that already spelled it.**
`EbeanTaskQueue.enqueue` and `EbeanImageRepository.save` each carried the same check: if
`TransactionControl.currentTransaction()` is not null, join it, otherwise open one and commit it.
Ebean's no-arg `beginTransaction()` already does exactly that: "Start a transaction with 'REQUIRED'
semantics. With REQUIRED semantics if an active transaction already exists that transaction will be
used" (`ebean-api-19.2.0-sources.jar`, `io/ebean/Database.java:475`, the javadoc block closing at
:550 above the no-arg declaration at :551). Measured against the real datasource: a task enqueued
inside a nested `inTransaction` whose outer block throws leaves no row.

So the check never did anything. It was written to obtain a behaviour the call underneath already
had, and it went unquestioned because it read as prudence. That is the shape worth recording: two
sites paying for a guarantee, and the library's own documentation settling it in one line.

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

**Each declaration must be complete, because the default `Database` has two creation paths.** The
CDI producer builds one; avaje-config builds another when a query bean reaches `DB.getDefault()`
first (a query bean constructed with no argument runs on the default database, and the worker's
poller does that during boot). Whichever runs first wins, and the loser is discarded in silence
("Using existing database with name:db"). The two paths read different sources: the producer
configures the pool in code, avaje-config reads only the properties file. So a property the producer
sets in code and the properties file omits is set or unset depending on a race.

Deleting `ebean.properties` surfaced this immediately: the suite moved to `:memory:` and the boot
failed with `no such table: tasks`. Each SQLite connection to `:memory:` gets its own private
database, so the connection that ran the migrations was not the connection the poller queried. The
producer pins the pool to one connection; the test properties did not. They do now, for the reason
`api-persistence-sqlite/src/test/resources/application-test.properties` already recorded for its own
suite. The shared file had been hiding this: every connection saw the same schema because they all
opened the same file.

## Decision 2: `TransactionRunner.inTransaction` is the only seam, and the library owns the semantics

`EbeanTaskQueue` and `EbeanImageRepository` depend on `TransactionRunner` and call
`inTransaction { }`. Their hand-rolled check goes, and nothing replaces it: `EbeanTransactionRunner`
keeps calling the no-arg `beginTransaction()`, whose REQUIRED semantics are the whole of what the
check was reproducing.

An adapter that needs a pair of statements to serialise says so by wrapping them, and says nothing
about who else is holding a transaction. Whether it is the outermost holder stops being its
business, which is the property that made the check duplicable in the first place.

Rejected: passing `TxScope.required()` explicitly. It names the behaviour at the call site, which
reads as documentation, and implies the default is something else. The default is this; the place to
write that down is a test, and there is one.

Rejected: hoisting the same `if` into `EbeanTransactionRunner`. It removes the duplication and keeps
a hand-rolled version of semantics the library already has, which is the smaller half of the
problem.

## Consequences

- No behaviour changes at all: this half is a refactor, and its own characterisation test says so by
  passing before and after.
- The seam now depends on a documented library guarantee rather than on a check in this repository.
  A future Ebean upgrade that weakened it would be caught by the nesting test, which is why that
  test is worth its line count.
- `TxScope.requiresNew()` has no user here. A future one is a deliberate addition to
  `TransactionControl`, not a return to the hand-rolled check.
- `TransactionControl.currentTransaction()` survives with no production caller, as the observation
  point for the test that pins `enqueue`'s transactional envelope. A port method kept for a test is
  a cost; the alternative is a refactor whose only guard is that the gate stays green, and the gate
  would stay green if the envelope went with the check.
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
