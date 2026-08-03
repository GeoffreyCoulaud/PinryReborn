# 0008. Structural isolation of soft-delete reads (confining the Database capability)

Status: Accepted
Date: 2026-08-03
Extends: `docs/adr/0007-single-representation-soft-delete.md`, whose decision 5 (build-time prohibition,
not compile-time impossibility) this leaves in force.

## Context

0007 chose explicit filtering and accepted a build-time guarantee. Its specification (section 4.6) named
three routes the prohibition leaves open: the inherited `BeanRepository` finders on `ModelRepository`, a
raw SQL predicate on `any()`, and an in-memory read after `any()`. The first is the route the codebase
actually exposes (`ModelRepository` extends `BeanRepository`, so every repository holding one inherits
`findAll` / `findById` / `findByIdOrNull` / `db()`), and it was recorded in the backlog. Behind it sits
the same shape in the wider handle: any `database.find(...)` on a recyclable type names no query bean and
writes no `softDeletedAt` predicate, so it is invisible to both Konsist assertions and the
`SoftDeleteStateFilteredOutsideQueries` detekt rule. Today nothing calls it, verified by grep across the
module: `database.` is used only for `save`, `delete`, `merge`, `reference`, `beginTransaction` and
`currentTransaction`. The capability is dormant, one constructor argument from being walked.

The structural question is whether to keep patching shapes (ban `BeanRepository`, then ban
`database.find`, then the next) or to close the set. The set is finite and closed by capability, not by
method: the only ways to root a read are to hold a `Database`, to extend `BeanRepository` /
`BeanFinder`, or to construct the query bean. 0007's assertion already confines the third to `queries`.
Banning the first two as production-visible capabilities closes the set, and a future `find` variant
Ebean may add needs the confined handle to be called, so it adds no route.

This does not reverse 0007 decision 5, which rejected type-splitting (`Pin` / `RecycledPin`) and separate
tables on the ground that recycling is a state, not a type, and that the guarantee is legitimately a
build-time prohibition. That stands. The compiler still cannot prove what a query returned, because the
state is a column value. What changes is the strength of the prohibition: the read capability is
confined, so a read cannot exist without going through `queries`, where the three state constructors
force the caller to name `active` / `recycled` / `any`.

## Decision

1. **The read capability is confined.** The `io.ebean.Database` type is referenced only in
   `EbeanDatabaseProducer` (which builds it) and two port implementations that wrap it. Nothing else in
   production holds a `Database`, so `database.find(...)` is not callable.
2. **Two persistence-internal ports carry what `Database` was used for, split by role.** `Persistor`
   exposes `save`, `delete`, `merge`, `reference`; `TransactionControl` exposes `beginTransaction`,
   `currentTransaction`. Neither reads. A repository that only writes receives `Persistor` only, which is
   the capability discipline this decision imposes on itself.
3. **`ModelRepository` no longer extends `BeanRepository`.** It holds `Persistor` and exposes
   `saveAndReturn` alone; the inherited finder surface leaves with the superclass. A Konsist assertion
   bars `BeanRepository` and `BeanFinder` as production supertypes, so the route does not reopen.
4. **Query beans drop their redundant `database` argument.** `QImageModel(database)`,
   `QImageDownloadModel(database)` and `QTaskModel(database)` become their no-arg forms (17 sites). The
   generated no-arg constructor is documented "Construct using the default Database" and resolves, under
   `defaultDatabase(true)`, to the same instance (kapt output `QImageModel.kt:45-49`, producer
   `EbeanDatabaseProducer.kt:21`). Verified against generated source at the pinned version.
5. **0007 decision 5 stands.** The guarantee remains build-time, not compile-time proof of the result.
   Routes 2 (raw SQL) and 3 (in-memory) stay open as 0007 recorded; this closes route 1 and its wider
   shape.
6. **The static read facades `io.ebean.DB` and `io.ebean.Ebean` are banned from production.** They are a
   read entry point that needs no `Database` instance (they operate on the default server), so confining
   the instance (decision 1) does not reach them: `io.ebean.DB.find(BoardModel::class.java, id)` reads a
   recyclable row unfiltered while passing the instance ban. A Konsist import ban plus a detekt rule
   (`DatabaseStaticFacadeCall`, FQN-proof - the `QueryBeanConstructedByQualifiedName` analog) close both the
   imported and the fully-qualified forms. Surfaced by the whole-branch review after the instance
   confinement landed; the closure spans instance, supertypes, facades and query beans.

## Consequences

- **No behaviour changes.** Reads and writes do the same work through narrower types. No migration, no
  API change.
- **`Database` is held in three files, not thirteen.** Every write goes through `Persistor`, every
  transaction boundary through `TransactionControl`, every read through `queries` or a non-recyclable
  query bean. The confinement is what a future reviewer reads.
- **The `ModelRepository` backlog item is delivered**, and the wider hole it named is closed in the same
  pass, because both are the same capability.
- **`TransactionControl` is a thin port whose value is confinement, not behaviour.** Without it,
  `EbeanTransactionRunner`, `EbeanTaskQueue` and `EbeanImageRepository` would hold `Database` for
  transactions and the allowlist would weaken.
- **Routes 2 and 3 remain.** A SQL escape hatch on `any()` and an in-memory read after a sanctioned query
  are still expressible; both are narrower than what this closes, and neither is used today.
- **The ambient-transaction logic in `EbeanTaskQueue` and `EbeanImageRepository` is not unified.** It now
  reads over `TransactionControl`; unifying it through `TransactionRunner` is filed in the backlog, since
  it changes Ebean nesting behaviour.
