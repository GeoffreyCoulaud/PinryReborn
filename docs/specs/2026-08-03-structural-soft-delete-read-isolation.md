# Structural isolation of soft-delete reads (closing the open routes after 0007)

Date: 2026-08-03
Status: Approved 2026-08-03
Branch: `refactor/soft-delete-read-isolation`
ADR: `docs/adr/0008-structural-soft-delete-read-isolation.md`
Extends: `docs/adr/0007-single-representation-soft-delete.md`, whose decision 5 this leaves in force.
The matching spec (`docs/specs/2026-07-29-single-representation-soft-delete.md` section 4.6) names three
routes that stay open after its build-time prohibition. This closes two of the three. The backlog item
"`ModelRepository` inherits Ebean finders that no soft-delete guard can see" is the first of the two,
delivered here as one slice of a single pass rather than alone.

## 1. Goal

Make an unfiltered read of a recyclable model structurally unexpressible outside the `queries` package,
by confining the only capability that can root one. 0007 chose explicit filtering over implicit
`@SoftDelete` and accepted that the guarantee would be a build-time prohibition rather than a
compile-time impossibility (its decision 5). That prohibition left three routes open, named in
`docs/specs/2026-07-29-single-representation-soft-delete.md` section 4.6:

1. `ModelRepository` extends `io.ebean.BeanRepository`, inheriting `findAll`, `findById`,
   `findByIdOrNull` and `db()`.
2. `raw("soft_deleted_at is null")` on `any()` writes the predicate in SQL, where the detekt rule reads
   Kotlin.
3. Reading `softDeletedAt` in memory after `any()` filters without a predicate either tool can see.

This pass closes route 1 and the wider shape behind it: any `database.find(...)` / `database.sqlQuery` /
`database.createQuery` on a recyclable type, which today names no query bean and writes no predicate and
so is invisible to every guard. Routes 2 and 3 stay open and out of scope: the residual risk after this
work, recorded as such.

The mechanism is capability confinement, not method enumeration. The only ways to root a read are to
hold a `Database`, to extend `BeanRepository` / `BeanFinder`, or to construct a query bean. Banning the
first two as production-visible capabilities closes the set: there is no fourth handle, so no `find`
variant needs listing.

## 2. Scope

**In scope:**

- Two persistence-internal ports: `Persistor` (writes) and `TransactionControl` (transaction lifecycle).
  Both wrap the single `Database` and expose nothing that reads.
- `ModelRepository` rewritten to hold `Persistor`, no longer extending `BeanRepository`. The
  `entityClass` constructor parameter goes (unnecessary to `merge`); its seven callers lose their
  `XModel::class` argument.
- Every site that today holds `Database` switches to the port its role needs: write-only repositories
  take `Persistor`; `EbeanTransactionRunner`, `EbeanTaskQueue` and `EbeanImageRepository` take
  `TransactionControl` (the last two take both).
- Every query bean constructed with an explicit `database` becomes its no-arg form: `QImageModel`,
  `QImageDownloadModel` and `QTaskModel` (17 sites across `EbeanImageRepository`,
  `EbeanImageDownloadRepository` and `EbeanTaskQueue`). The generated no-arg constructor resolves to the
  default `Database` (`QImageModel.kt:45-46` in the kapt output), which `EbeanDatabaseProducer` sets with
  `defaultDatabase(true)` (`EbeanDatabaseProducer.kt:21`), so the two forms resolve to the same instance.
- Two Konsist assertions: the `io.ebean.Database` type is imported only in the producer and the two port
  implementations; and no production class extends `BeanRepository` or `BeanFinder`.

**Out of scope:**

- Route 2 (raw SQL predicate on `any()`) and route 3 (in-memory read after `any()`). Both remain, as
  0007 recorded.
- Splitting recyclable types (`Pin` / `RecycledPin`) or moving recycled rows to a separate table.
  Rejected on the same ground as 0007 decision 5: it invents a type distinction the business does not
  make, and trades a filtering mistake for a referential-integrity one. This pass extends 0007's
  enforcement; it does not reverse its representation.
- The hand-rolled "join ambient or open my own" transaction logic in `EbeanTaskQueue` and
  `EbeanImageRepository`. Routing it through `TransactionRunner` would change Ebean's nesting behaviour
  and is its own lot, filed in the backlog.
- Behaviour changes. Reads and writes do the same thing before and after; only the held type changes.
  No migration.

## 3. Decisions (invariants)

- **D1. The read capability is the `Database` type, plus `BeanRepository` / `BeanFinder` as
  supertypes.** A read of a recyclable model is rooted by holding one of these and calling a method that
  names no state. Banning the capabilities closes the set: there is no fourth handle, so no `find*`
  variant is enumerated.
- **D2. Two ports, split by role, both persistence-internal.** `Persistor` carries writes (`save`,
  `delete`, `merge`, `reference`); `TransactionControl` carries transaction lifecycle
  (`beginTransaction`, `currentTransaction`). Neither reads. Splitting follows the project convention
  that a dependency is a dedicated type carrying one role, and it practices the capability discipline
  this pass imposes: a repository that only writes does not receive `beginTransaction`.
- **D3. `io.ebean.Database` is referenced only in `EbeanDatabaseProducer` and the two port
  implementations.** Konsist-enforced. Because nothing else holds the type, `database.find(...)` is not
  callable in production outside the sanctioned homes.
- **D4. `BeanRepository` and `BeanFinder` are not production supertypes.** Konsist-enforced.
  `ModelRepository` is rewritten to hold `Persistor`; the inherited finder surface disappears with the
  superclass.
- **D5. The guarantee stays build-time, not compile-time proof of the result.** The state is a column
  value, a runtime fact; the compiler cannot prove what a query returned. What this pass guarantees is
  that a read cannot exist without naming its state, because the only read path left constructs the
  recyclable query bean inside `queries` (0007's assertion 2), where the three state constructors live.
  Consistent with 0007 decision 5; this tightens the prohibition, it does not reach for an
  impossibility.
- **D6. Non-recyclable query beans are untouched except where they held `Database` unnecessarily.** They
  carry no state to filter, so direct construction stays. Only the explicit `database` argument is
  dropped, and only because it is provably redundant under `defaultDatabase(true)`.

## 4. Design

### 4.1 The ports

```kotlin
// persistence.sqlite, internal
internal interface Persistor {
    fun save(bean: Any)
    fun delete(bean: Any)
    fun merge(bean: Any)
    fun <T : Any> reference(type: Class<T>, id: Any): T
}

internal interface TransactionControl {
    fun beginTransaction(): io.ebean.Transaction
    fun currentTransaction(): io.ebean.Transaction?
}
```

Two implementations, each taking the `Database` the producer supplies:

```kotlin
@ApplicationScoped
class EbeanPersistor(private val database: Database) : Persistor { /* delegate */ }

@ApplicationScoped
class EbeanTransactionControl(private val database: Database) : TransactionControl { /* delegate */ }
```

`io.ebean.Transaction` leaks no further than the persistence adapter (it never reaches the domain); the
domain's `TransactionRunner.inTransaction { }` is unchanged.

### 4.2 Consumers

Every site that today imports `io.ebean.Database` switches to the port its role needs. Twelve consumers
today; after the switch, `Database` lives in three files.

| Consumer | Today holds | After |
|---|---|---|
| `ModelRepository` (extends `BeanRepository`) | `Database` (via superclass) | `Persistor` |
| `BoardRepository`, `PinRepository`, `UserRepository`, `TagRepository`, `SessionTokenRepository`, `UserDataExportRepository`, `UserPasswordHashRepository` | `Database` | `Persistor` |
| `EbeanImageRepository` | `Database` (write, tx, image query beans) | `Persistor` + `TransactionControl` |
| `EbeanImageDownloadRepository` | `Database` (write, image query beans) | `Persistor` |
| `EbeanTaskQueue` | `Database` (write, tx, `QTaskModel`) | `Persistor` + `TransactionControl` |
| `EbeanTransactionRunner` | `Database` (tx) | `TransactionControl` |

### 4.3 `ModelRepository`

```kotlin
internal class ModelRepository<T : BaseModel>(
    private val persistor: Persistor,
) {
    fun saveAndReturn(model: T): T = model.also { persistor.merge(it) }
}
```

`BeanRepository` is gone; `entityClass` is gone (unnecessary to `merge`, which resolves the descriptor
from the bean's class). `saveAndReturn` keeps its body, delegating `merge` to the port. The seven callers
stop passing `XModel::class`.

### 4.4 Dropping the redundant `database` from query beans

Three repositories construct query beans with an explicit `database`: `EbeanImageRepository`
(`QImageModel(database)`, 4 sites), `EbeanImageDownloadRepository` (`QImageDownloadModel(database)`, 4
sites) and `EbeanTaskQueue` (`QTaskModel(database)`, 9 sites). The generated source settles that the
explicit argument is redundant:

- `QImageModel.kt:45-46` (kapt output): `/** Construct using the default Database. */ constructor() :
  super(ImageModel::class.java)`.
- `QImageModel.kt:48-49`: `/** Construct with a given Database. */ constructor(database: Database) :
  super(ImageModel::class.java, database)`.
- `EbeanDatabaseProducer.kt:21`: `.defaultDatabase(true)`, so the default `Database` is the produced one.

The no-arg and the explicit forms resolve to the same instance; behaviour is identical for query
execution and transaction binding (single server). The 17 sites become `QImageModel()` /
`QImageDownloadModel()` / `QTaskModel()`, after which the three repositories no longer need `Database`
for query construction, only for the writes and (for `EbeanImageRepository` and `EbeanTaskQueue`) the
ambient-transaction checks that move to the ports.

### 4.5 Structural enforcement

Two Konsist assertions in `ArchitectureKonsistTest`, alongside the soft-delete assertions from 0007:

1. **`Database` confined.** No production file outside the producer and the two port implementations
   imports `io.ebean.Database`.
2. **No Ebean finder supertype.** No production class has `BeanRepository` or `BeanFinder` as a
   supertype.

Each arrives with the mutation that makes it fail, pasted in its commit body (the project convention for
a structural assertion): assertion 1 fails while any repository still imports `Database`; assertion 2
fails while `ModelRepository` still extends `BeanRepository`. The existing assertion from 0007 (no
recyclable query bean imported outside `queries` / `pagination`) stays and is what keeps the query-bean
route closed.

### 4.6 Why the set is closed

A read of a recyclable model needs one of: a `Database` reference, a `BeanRepository` / `BeanFinder`
supertype, or the recyclable query bean. D3 removes the first everywhere it is not sanctioned; D4 removes
the second; 0007's assertion removes the third outside `queries`. No fourth handle exists in Ebean's read
surface, so the closure is by capability, not by listing `find` / `findOne` / `findList` / `sqlQuery` /
`createQuery` one by one. A read method added to `Database` in a future Ebean version adds no route,
because it still needs the confined handle to be called.

## 5. Testing strategy

Strict TDD, red before green, the failing test committed alone with the command and its output. Project
order: integration, use-case, repository.

1. **`Persistor` and `TransactionControl` are unit-tested at their boundary.** Each method delegates to
   the wrapped `Database`, asserted through a fake. The type itself is the guarantee that no read is
   reachable; the test documents it rather than substituting for it.
2. **Repository tests are the safety net for the switch.** They are green before and after; no assertion
   is edited. `saveAndReturn`, `save`, `delete`, `reference`, the ambient-transaction paths in
   `EbeanImageRepository` and `EbeanTaskQueue`, and the image query-bean reads and writes all keep working
   through the ports.
3. **Each Konsist assertion fails red against the current sources** before the code satisfies it, pasted
   in the commit that introduces it; then green after. The mutation form (flip the code, watch the
   assertion fail) is the proof the assertion holds something, per the convention settled 2026-07-29.
4. **The authentication end-to-end test stays green.** A tombstoned account cannot obtain a session. This
   is the security-bearing behaviour 0007 moved to `UserQueries.active()`; this pass must not disturb it.

## 6. Risks and accepted trade-offs

- **Routes 2 and 3 stay open.** A `raw("...")` SQL fragment or an in-memory read after `any()` can still
  express "is it recycled" outside `queries`, invisible to the guards. Recorded in 0007; this pass does
  not touch them. They are narrower than the closed routes (one is a SQL escape hatch on `any()`, the
  other reads a value already loaded through a sanctioned query) and neither is the shape the codebase
  exposes today.
- **The guarantee is gate-time, not compile-time.** Consistent with 0007 decision 5. The residual is "a
  developer could call the wrong constructor (`any()` for `active()`)", which is visible at the call site
  and reviewable, not invisible.
- **`TransactionControl` is a thin port.** Two methods delegating to `Database`. Its cost is one type;
  its value is keeping `Database` confined. Without it, `EbeanTransactionRunner`, `EbeanTaskQueue` and
  `EbeanImageRepository` would hold `Database` for transactions and the allowlist would widen from three
  files to six, weakening D3.
- **The query-bean-with-database simplification rests on a generated-source reading.** Verified against the
  kapt output at the pinned version (section 4.4), not against recall. If a future Ebean version changed
  the default-server resolution, the Konsist confinement would still hold; only the simplification's
  equivalence would need re-checking.
- **The ambient-transaction logic is not unified.** `EbeanTaskQueue` and `EbeanImageRepository` keep
  their "join ambient or open my own" check, now over `TransactionControl`. Unifying it through
  `TransactionRunner` is behaviour-risky (Ebean nesting) and is filed in the backlog.
