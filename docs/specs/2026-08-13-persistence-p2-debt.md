# Three persistence defects: a test database that is not the one declared, a duplicated transaction seam, and three indexes that serve nothing

Date: 2026-08-13
Status: Approved 2026-08-13
Branch: `refactor/persistence-p2-debt`
ADR: `docs/adr/0012-one-datasource-declaration-and-one-transaction-seam.md`

Closes three P2 items in one lot. They share a module and nothing else, so each half below stands
alone; the order is the dependency order, not a narrative.

## 1. Goal

1. **The integration tests run on the database their configuration declares.** Today they run on a
   file, `api-application/data.db`, shared between runs.
2. **One way to open a transaction in the persistence adapter.** Today two adapters hand-roll a
   "join the ambient transaction or open my own" check that `TransactionRunner` should own.
3. **The indexes on `tasks` serve the queries they were created for, or they go.**

## 2. The measurement

### 2.1 The test database

`api-application/src/test/resources/application.properties:5` declares
`datasource.db.url=jdbc:sqlite::memory:`. Moving the on-disk file aside and running one integration
test brings it back, with the row the test wrote inside it:

```
$ mv api-application/data.db /tmp/.../data.db.baseline
$ ./gradlew :api-application:test --tests "UserCreationIntegrationTest"
Loading properties from ebean.properties is deprecated. Please migrate to use application.yaml or application.properties instead.
Using existing database with name:db
BUILD SUCCESSFUL in 6s
$ sqlite3 api-application/data.db "select 'users', count(*) from users union all select 'migrations', count(*) from db_migration;"
users|1
migrations|19
```

The two log lines name the mechanism. `api-persistence-sqlite/src/main/resources/ebean.properties`
lives in `main`, so it travels on the classpath of every downstream module, tests included, and it
declares `datasource.db.url=jdbc:sqlite:${DB_PATH:data.db}?...`. Ebean bootstraps a database named
`db` from it through avaje-config before the CDI producer runs, and `EbeanDatabaseProducer`'s
`Database.builder()` then finds that name already registered and returns it: "Using existing
database with name:db". The `:memory:` URL the producer was given is dropped on the floor, silently.

So the integration suite writes user rows, pins, tasks and session tokens into a file that survives
between runs, is shared by two concurrent runs, and keeps a migration applied in `db_migration`
after its `.sql` is deleted. The backlog flagged this as a candidate cause for the `SQLITE_BUSY`
that the 2026-08-12 triage closed as unreproduced; this lot does not claim to settle that question,
it only removes the condition.

The same trap was already paid for once inside `api-persistence-sqlite`: the header of
`src/test/resources/application-test.properties` records that its predecessor was named
`ebean-test.properties`, matched no avaje-config overlay name, and left the repository tests running
on this same `data.db`. That file's rename fixed the module. It could not fix `api-application`,
whose classpath carries `ebean.properties` but not another module's test resources.

### 2.2 The transaction seam

`EbeanTaskQueue.enqueue` (`EbeanTaskQueue.kt:47`) and `EbeanImageRepository.save`
(`EbeanImageRepository.kt:22`) carry the same eight lines and the same four-line comment: if
`transactionControl.currentTransaction() != null` join it, else open one and commit it. That is
`TxScope.required()` spelled by hand, and Ebean already offers it. From the sources of the pinned
version (`ebean-api-19.2.0-sources.jar`, `io/ebean/Database.java:582`):

```
 * <h3>REQUIRED example:</h3>
 * // start a new transaction if there is not a current transaction
 * try (Transaction txn = database.beginTransaction(TxScope.required())) {
 *   // commit the transaction if it was created or
 *   // do nothing if there was already a current transaction
 *   txn.commit();
 * }
```

`EbeanTransactionRunner.inTransaction` does not use it: it calls `beginTransaction()`, so nesting
two `inTransaction` blocks today opens two transactions.

### 2.3 The three indexes

Measured and recorded by the T3 review of the 2026-08-12 triage, restated here as the input this
lot acts on. SQLite does not use a partial index when the value its predicate tests arrives as a
bound parameter, and Ebean binds: `claimNext` plans as `SCAN tasks` plus
`USE TEMP B-TREE FOR ORDER BY`, and the dedup lookup as `SCAN`. So of the three partial indexes
created by `1.3.sql:23,25,27`, two cost writes and buy nothing, and the third enforces its
uniqueness but does not speed its own lookup.

## 3. What is done

### 3.1 One declaration of the datasource

**Delete `api-persistence-sqlite/src/main/resources/ebean.properties`.** Everything it declares is
already declared where it belongs:

| What it declares | Who owns it after |
|---|---|
| ddl generate/run, migration run/path, entity packages | `EbeanDatabaseProducer`, programmatically |
| datasource url, driver, credentials | `application.properties` per module, read by the producer |
| the same three for the repository test suite | `api-persistence-sqlite/src/test/resources/application-test.properties` |

The file being in `main` is not a detail to work around: a test-only datasource declared in a
published resource is the defect. Deleting it leaves one declaration per runtime and none that
travels.

**Acceptance criteria.**

1. A full test run creates no `.db` file anywhere in the tree:
   `find . -name '*.db' -o -name '*.db-wal'` is empty after `./gradlew gate`.
2. A test asserts the running integration database is in memory, so a future reintroduction fails
   the gate rather than the operator's `ls`. The assertion reads
   `pragma_database_list` through the `database` already exposed by `IntegrationTest`, and joins an
   existing `@QuarkusTest` suite rather than adding a boot.
3. The repository test suite (`api-persistence-sqlite`) still runs, unchanged, on its own
   `application-test.properties`.

**Known risk, stated before the work.** The suite has been running on a file with state surviving
between runs since before this lot. A test that silently depended on that state will now fail. Such
a failure is the defect surfacing, not a regression introduced here: it gets fixed in this lot if it
is a test defect, and reported as a finding if it is a product defect.

### 3.2 One transaction seam

`TransactionRunner.inTransaction` becomes the only way an adapter opens a transaction, and it takes
REQUIRED semantics.

- `TransactionControl.beginTransaction()` delegates to `database.beginTransaction(TxScope.required())`.
- `EbeanTaskQueue` and `EbeanImageRepository` depend on `TransactionRunner` instead of
  `TransactionControl`, and their `if` disappears; `claimNext`'s explicit commit goes with it.
- `TransactionControl.currentTransaction()` is deleted if this leaves it without a caller.

**Acceptance criteria.**

1. `currentTransaction()` appears in no production source (it may survive in a test as an
   observation point only if a test needs it).
2. A test proves the join: a write issued from inside `inTransaction { }` that then rolls back
   leaves no row, for `enqueue` and for `ImageRepository.save`.
3. A test proves nesting is flat: `inTransaction { inTransaction { } }` commits once, and a rollback
   of the outer block discards the inner write.
4. Behaviour under no ambient transaction is unchanged: `enqueue`'s dedup check-then-insert and
   `claimNext`'s select-then-update each still run inside one transaction (design invariant "One
   connection; a transaction is what serialises a pair of statements").

### 3.3 The indexes on `tasks`

| Index | Decision |
|---|---|
| `ix_tasks_claim` | Recreated non-partial, `state` first: `(state, priority desc, available_at asc, id asc)`. The equality on `state` binds, and the remaining columns serve the `ORDER BY`. |
| `ix_tasks_lease` | Dropped. `RUNNING` rows are bounded by `worker_count`, and `ix_tasks_state_terminal_state_at` already offers a `state` prefix for `reapExpired`. |
| `ux_tasks_dedup` | Untouched. Its partial predicate is the semantics (uniqueness among live tasks), not a failed optimisation, and it is pinned by `PartialUniqueIndexStates`. |

Rejected: inlining the `'PENDING'` literal into the claim query. It puts raw SQL in the adapter to
suit a planner quirk and breaks at the first multi-state query.

**Acceptance criteria.**

1. `EXPLAIN QUERY PLAN` for the claim query as Ebean builds it (`Query.getGeneratedSql()`, bound
   parameters) reports `SEARCH tasks USING INDEX ix_tasks_claim` and no `USE TEMP B-TREE FOR ORDER
   BY`. Pasted before and after in the commit message.
2. The change is a new migration generated by `generateDbMigration` from the changed `@Index`
   definitions on `TaskModel`, never an edit of `1.3.sql`. Precedent for a generated index drop:
   `1.15.sql`, `drop index if exists ix_tasks_state_when_modified`.
3. `DbMigrationModelCoverageTest` and `PartialUniqueIndexStatesTest` stay green; the second should
   be untouched, since neither dropped index is unique.

## 4. Out of scope

- The `SQLITE_BUSY` closed as unreproduced on 2026-08-12. This lot removes a candidate cause and
  claims nothing more.
- Periodic maintenance through the task queue, and inverse associations on the persistence models:
  both stay open P2 items.
- Any change to the production datasource URL, its pragmas, or the single-connection pool.
- `%prod` configuration and the container image.

## 5. Gate and integration

The gate is the single knob and runs whole. Integration is a rebased PR reviewed by the operator,
per `agents/workflow.md`. Three commits at least, one per half, so a revert of one does not carry
the others.
