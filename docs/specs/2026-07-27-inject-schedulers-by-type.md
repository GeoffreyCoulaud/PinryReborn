# Inject worker schedulers by type

Date: 2026-07-27
Status: pending approval
Branch: `refactor/inject-schedulers-by-type`
Depends on: the worker runtime (`TaskRuntimeProducers`, `TaskWorkerLifecycle`,
`ExportRetentionLifecycle`, `GarbageCollectionLifecycle`), the CDI wiring in `api-application/wiring/`,
the `ArchitectureKonsistTest`. No new external dependency, no migration.

## 1. Goal

Remove the last two string-qualifier injections in the codebase. `TASK_POLL_SCHEDULER` and
`EXPORT_PURGE_SCHEDULER` are `@Identifier("...")` qualifiers on a raw JDK `ScheduledExecutorService`,
so a consumer depends on a producer's name rather than on a type that carries its role. The
inject-by-type convention (persisted in `agents/project.md` on this same branch) says a dependency is a
dedicated type and the container provides the instance. The garbage collection scheduler was already
migrated to a dedicated type (`GarbageCollectionExecutor`); the two worker schedulers are the remaining
debt, recorded in that same convention's "not in scope" caveat.

The qualifier existed only to lift the ambiguity with Quarkus's synthetic `@Default` bean for the raw
`ScheduledExecutorService` / `ExecutorService` / `Executor` types (documented in the
`TASK_POLL_SCHEDULER` KDoc). A scheduler the consumer receives as a plain constructor parameter, wired
by hand in the composition root, resolves that ambiguity by construction, with no qualifier.

This also enforces the convention structurally: a Konsist test forbids `@Identifier` anywhere in
production, so the debt cannot return.

## 2. Scope

**In scope:**

- One interface `PeriodicScheduler` and one implementation `SingleThreadPeriodicScheduler` in
  `api-worker-quarkus`, replacing the three scheduler shapes (the two qualified
  `ScheduledExecutorService` beans and the dedicated `GarbageCollectionExecutor`).
- The three lifecycles (`TaskWorkerLifecycle`, `ExportRetentionLifecycle`,
  `GarbageCollectionLifecycle`) receive their scheduler as a plain `PeriodicScheduler` constructor
  parameter, with no `@Identifier`. They stop being self-discovered `@ApplicationScoped` beans and are
  produced from the composition root instead (D3, validated by spike).
- A `WorkerLifecycleProducers` in `api-application/wiring/` produces the three lifecycles, each with
  its own `SingleThreadPeriodicScheduler()` wired by hand. Three producers, three instances, three
  threads: the isolation the qualifiers used to buy is visible in the wiring itself.
- A Konsist test in `ArchitectureKonsistTest` forbids importing `io.smallrye.common.annotation.Identifier`
  in production.
- `agents/project.md` loses the "not in scope" caveat on the inject-by-type convention (the debt is
  resolved).

**Out of scope:**

- The other producers in `TaskRuntimeProducers` (`backoffPolicy`, `taskHandlerRegistry`,
  `workerExecutor`) stay where they are; only the scheduler wiring moves.
- No change to the lifecycle logic (`start` / `stop` / `safe*`), to the sweeps, or to any use case.
- No migration, no new port, no config change.
- `@Identifier` is not banned as a Quarkus feature, only in this codebase's production sources; the
  Konsist test encodes the convention, and a genuine future need disables it inline with a reason.

## 3. Decisions (invariants)

Settled in discussion; the ADR is `docs/adr/0004-inject-schedulers-by-type.md`.

- **D1: one interface, one implementation, not one type per scheduler.** The three schedulers expose
  the same surface (`scheduleWithFixedDelay` + `shutdown`) and each is an isolated single thread. A
  dedicated type per scheduler (the shape `GarbageCollectionExecutor` already had) would triplicate an
  identical interface and wrapper for no information gain: the role is carried by the wiring (which
  lifecycle consumes which instance), not by the type. One `PeriodicScheduler` with one
  `SingleThreadPeriodicScheduler` is the minimum that carries the contract.
- **D2: the scheduler is wired explicitly in the composition root.** A `WorkerLifecycleProducers` in
  `api-application/wiring/` produces the three lifecycles, and each producer instantiates
  `SingleThreadPeriodicScheduler()` and passes it to the constructor. The isolation (one thread per
  role) is now stated in plain code a reader can follow without CDI knowledge, unlike an implicit
  `@Dependent` whose "instance per injection" semantics are invisible at the call site.
- **D3: the lifecycles are produced, not self-discovered; their `@Observes` methods still fire.**
  Producing a lifecycle means the class loses its `@ApplicationScoped` annotation, which raised whether
  ArC still discovers and calls its `@Observes StartupEvent` / `ShutdownEvent`. Validated empirically: a
  spike produced `TaskWorkerLifecycle` via `@Produces @ApplicationScoped`, and
  `TaskQueueBootIntegrationTest` still passed, proving the poller started (the enqueued task settled to
  DEAD), so the `@Observes StartupEvent` fired on the produced bean. The three lifecycles are therefore
  produced from the composition root with no startup regression.
- **D4: the convention is enforced structurally.** A Konsist test asserts no production source imports
  `io.smallrye.common.annotation.Identifier`. After the refactor there is no legitimate use (verified by
  search: the three scheduler sites are the only occurrences), so the test has no false positives; a
  future need suppresses it inline with a reason, which is the point of encoding the convention as a
  failing build rather than a paragraph.
- **D5: the `GarbageCollectionExecutor` dedicated type is removed, not kept alongside.** GC migrates to
  `PeriodicScheduler` like the other two. Keeping `GarbageCollectionExecutor` as a fourth type would
  reintroduce the duplication D1 rejects and leave the codebase with two spellings of the same concept.
  The GC lifecycle, its producer and its test move to `PeriodicScheduler`.

## 4. Changes

### 4.1 New types (`api-worker-quarkus`)

```kotlin
interface PeriodicScheduler {
    fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit)
    fun shutdown()
}

class SingleThreadPeriodicScheduler : PeriodicScheduler {
    private val delegate = Executors.newSingleThreadScheduledExecutor()
    override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit) =
        delegate.scheduleWithFixedDelay(command, initialDelay, period, unit)
    override fun shutdown() = delegate.shutdown()
}
```

### 4.2 Lifecycles

`TaskWorkerLifecycle`, `ExportRetentionLifecycle`, `GarbageCollectionLifecycle`: the scheduler
constructor parameter changes type from `ScheduledExecutorService` (the first two) /
`GarbageCollectionExecutor` (GC) to `PeriodicScheduler`, and loses its `@Identifier`. The class loses
its `@ApplicationScoped` annotation (it is produced from the composition root instead, D3). The body is
unchanged: each already calls only `scheduleWithFixedDelay` and `shutdown`, and each keeps its
`@Observes StartupEvent` / `ShutdownEvent` methods.

### 4.3 Wiring

A new `api-application/wiring/WorkerLifecycleProducers.kt`:

```kotlin
@ApplicationScoped
class WorkerLifecycleProducers {
    @Produces
    @ApplicationScoped
    fun taskWorkerLifecycle(
        dispatcher: TaskDispatcher,
        reapExpiredTasks: ReapExpiredTasks,
        workerExecutor: WorkerExecutor,
        config: TaskQueueConfig,
    ) = TaskWorkerLifecycle(dispatcher, reapExpiredTasks, workerExecutor, SingleThreadPeriodicScheduler(), config)

    @Produces
    @ApplicationScoped
    fun exportRetentionLifecycle(
        reapExpiredUserDataExports: ReapExpiredUserDataExports,
        config: ExportsConfig,
    ) = ExportRetentionLifecycle(reapExpiredUserDataExports, SingleThreadPeriodicScheduler(), config)

    @Produces
    @ApplicationScoped
    fun garbageCollectionLifecycle(
        reapExpiredSessionTokens: ReapExpiredSessionTokens,
        reapOrphanedStorage: ReapOrphanedStorage,
        reapTombstonedAccounts: ReapTombstonedAccounts,
        reapTerminalTasks: ReapTerminalTasks,
        config: GarbageCollectionConfig,
    ) = GarbageCollectionLifecycle(
        reapExpiredSessionTokens, reapOrphanedStorage, reapTombstonedAccounts, reapTerminalTasks,
        SingleThreadPeriodicScheduler(), config,
    )
}
```

The three scheduler producers in `TaskRuntimeProducers` (`pollScheduler`, `exportPurgeScheduler`,
`garbageCollectionExecutor`) are deleted, along with the `TASK_POLL_SCHEDULER` /
`EXPORT_PURGE_SCHEDULER` constants and the `Identifier` import.

### 4.4 Konsist test

In `ArchitectureKonsistTest`, an assertion that no production source imports the qualifier. Following
the file's established pattern (filter to the offenders, finish on `assertEmpty` so a break names
every culprit):

```kotlin
@Test
fun `Given production sources, Then none imports the Identifier string qualifier`() {
    Konsist
        .scopeFromProduction()
        .imports
        .withName("io.smallrye.common.annotation.Identifier")
        .assertEmpty()
}
```

### 4.5 Documentation

`agents/project.md`: the inject-by-type invariant loses its "The pre-existing TASK_POLL_SCHEDULER /
EXPORT_PURGE_SCHEDULER schedulers predate this rule and are not in scope." sentence. The convention
now holds with no caveat.

## 5. Testing strategy

TDD, red before green, 100% branch per package.

1. **`SingleThreadPeriodicSchedulerTest` (new, `api-worker-quarkus`):** the implementation is inside the
   coverage perimeter, so both methods are covered. `scheduleWithFixedDelay` runs a task that flips a
   flag from a worker thread (proving delegation to the real executor), and `shutdown` terminates it so
   the test does not leak a thread.
2. **Lifecycle tests (existing, amended):** the three tests change the type of their scheduler mock from
   `ScheduledExecutorService` / `GarbageCollectionExecutor` to `PeriodicScheduler`. No assertion
   changes: the lifecycles call the same two methods.
3. **Konsist test:** fails red before the refactor (the three worker files still import `Identifier`),
   then passes once they drop it. The red is committed before the green per the TDD cycle.
4. **End-to-end (existing, untouched):** `TaskQueueBootIntegrationTest` (which the spike ran against a
   produced `TaskWorkerLifecycle` to confirm `@Observes StartupEvent` still fires) and the GC /
   export-retention boot paths confirm the three produced lifecycles still start on boot and run on
   their own threads.

## 6. Acceptance criteria

- `rg "@Identifier"` over production sources returns nothing.
- `rg "TASK_POLL_SCHEDULER|EXPORT_PURGE_SCHEDULER"` returns nothing.
- `GarbageCollectionExecutor` and `SingleThreadGarbageCollectionExecutor` no longer exist; the GC
  lifecycle injects `PeriodicScheduler`.
- `PeriodicScheduler` and `SingleThreadPeriodicScheduler` exist in `api-worker-quarkus`, with a unit
  test covering both methods at 100% branch.
- `WorkerLifecycleProducers` exists in `api-application/wiring/` and produces the three lifecycles, each
  with its own `SingleThreadPeriodicScheduler()`.
- `ArchitectureKonsistTest` has the `Identifier`-import assertion, and it fails if the import is
  reintroduced.
- `./gradlew gate` is green, 100% branch coverage per package maintained.
- `agents/project.md` inject-by-type invariant carries no "not in scope" caveat.

## 7. Risks and accepted trade-offs

- **Producing the lifecycles changes how ArC instantiates them.** The class loses `@ApplicationScoped`
  and is created by a producer method. The risk was that ArC would stop discovering the `@Observes`
  methods; validated otherwise by the spike (D3). No residual risk identified.
- **Removing `GarbageCollectionExecutor` touches shipped, tested code.** The change is
  behaviour-preserving (the GC lifecycle still schedules on its own single thread), and its test changes
  only the mock type. Accepted because keeping a fourth type would contradict D1.
- **The producer is outside the coverage perimeter.** `WorkerLifecycleProducers` lives in
  `api-application`, which is outside the Kover gate by design (composition root, end-to-end tests
  only). It is exercised by the boot integration tests. No perimeter change.
