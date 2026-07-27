# Inject worker schedulers by type

Date: 2026-07-27
Status: pending approval (revised: `@Dependent` wiring)
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
`TASK_POLL_SCHEDULER` KDoc). A scheduler the consumer asks for by type, with its scope made explicit on
the producer, resolves that ambiguity by construction, with no qualifier.

This also enforces the convention structurally: a Konsist test forbids `@Identifier` anywhere in
production, so the debt cannot return.

## 2. Scope

**In scope:**

- One interface `PeriodicScheduler` and one implementation `SingleThreadPeriodicScheduler` in
  `api-worker-quarkus`, replacing the three scheduler shapes (the two qualified
  `ScheduledExecutorService` beans and the dedicated `GarbageCollectionExecutor`).
- The three lifecycles (`TaskWorkerLifecycle`, `ExportRetentionLifecycle`,
  `GarbageCollectionLifecycle`) inject `PeriodicScheduler` by type, with no `@Identifier`. They stay
  self-discovered `@ApplicationScoped` beans (D3).
- A `SchedulerProducers` in `api-application/wiring/` produces `PeriodicScheduler` as a `@Dependent`
  bean, so each lifecycle gets its own instance (its own thread); the wiring sits in the composition
  root, next to `GarbageCollectionProducers`.
- A Konsist test in `ArchitectureKonsistTest` forbids importing `io.smallrye.common.annotation.Identifier`
  in production.
- `agents/project.md` loses the "not in scope" caveat on the inject-by-type convention (the debt is
  resolved).

**Out of scope:**

- The other producers in `TaskRuntimeProducers` (`backoffPolicy`, `taskHandlerRegistry`,
  `workerExecutor`) stay where they are; only the scheduler wiring moves.
- No change to the lifecycle logic (`start` / `stop` / `safe*`), to the sweeps, or to any use case.
- No migration, no new port, no config change.
- Producing the lifecycles themselves is out of scope: it is unreachable in ArC (D3).
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
- **D2: the scheduler is wired as a `@Dependent` bean in the composition root.** A `SchedulerProducers`
  in `api-application/wiring/` produces `PeriodicScheduler` via `@Produces @Dependent`, instantiating
  `SingleThreadPeriodicScheduler()`. `@Dependent` is the CDI pseudo-scope that yields a fresh instance
  per injection point, so the three lifecycles obtain three distinct instances (three threads), and the
  isolation the qualifiers used to buy is preserved without a qualifier. `@Dependent` is an explicit
  annotation: a reader who knows it understands the isolation directly, and one who does not sees the
  annotation and can look it up, which is the opposite of an implicit default scope (no annotation).
- **D3: the lifecycles stay self-discovered `@ApplicationScoped` beans; only the scheduler is produced.**
  The lifecycles keep `@ApplicationScoped` and their `@Observes StartupEvent` / `ShutdownEvent`. An
  earlier draft proposed producing the lifecycles (dropping `@ApplicationScoped`, keeping `@Observes`),
  but a build-time check showed it is unreachable in ArC: a class that declares `@Observes` is
  discovered as a `@Dependent` class-bean even without a scope annotation, so its constructor must
  resolve independently of any producer method, and a `PeriodicScheduler` parameter has no bean to
  satisfy it. Keeping the lifecycles discovered and producing only the scheduler avoids that trap and
  needs no bootstrap.
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
`GarbageCollectionExecutor` (GC) to `PeriodicScheduler`, and loses its `@Identifier`. The class keeps
its `@ApplicationScoped` annotation and its `@Observes StartupEvent` / `ShutdownEvent` methods (D3). The
body is unchanged: each already calls only `scheduleWithFixedDelay` and `shutdown`.

### 4.3 Wiring

A new `api-application/wiring/SchedulerProducers.kt`:

```kotlin
@ApplicationScoped
class SchedulerProducers {
    @Produces
    @Dependent
    fun periodicScheduler(): PeriodicScheduler = SingleThreadPeriodicScheduler()
}
```

`@Dependent` yields one instance per injection point, so each of the three lifecycles gets its own
`SingleThreadPeriodicScheduler` (its own thread). The three scheduler producers in
`TaskRuntimeProducers` (`pollScheduler`, `exportPurgeScheduler`, `garbageCollectionExecutor`) are
deleted, along with the `TASK_POLL_SCHEDULER` / `EXPORT_PURGE_SCHEDULER` constants and the `Identifier`
import.

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
4. **End-to-end (existing, untouched):** every `@QuarkusTest` boots the full application, so all three
   lifecycles fire `@Observes StartupEvent` and resolve their `PeriodicScheduler` dependency; a missing
   bean would fail every boot with `UnsatisfiedDependencyException`. The three-distinct-instances
   property (one thread per lifecycle) is the CDI `@Dependent` guarantee, not an asserted test outcome.

## 6. Acceptance criteria

- `rg "@Identifier"` over production sources returns nothing.
- `rg "TASK_POLL_SCHEDULER|EXPORT_PURGE_SCHEDULER"` returns nothing.
- `GarbageCollectionExecutor` and `SingleThreadGarbageCollectionExecutor` no longer exist; the GC
  lifecycle injects `PeriodicScheduler`.
- `PeriodicScheduler` and `SingleThreadPeriodicScheduler` exist in `api-worker-quarkus`, with a unit
  test covering both methods at 100% branch.
- `SchedulerProducers` exists in `api-application/wiring/` and produces `PeriodicScheduler` via
  `@Produces @Dependent`.
- `ArchitectureKonsistTest` has the `Identifier`-import assertion, and it fails if the import is
  reintroduced.
- `./gradlew gate` is green, 100% branch coverage per package maintained.
- `agents/project.md` inject-by-type invariant carries no "not in scope" caveat.

## 7. Risks and accepted trade-offs

- **`@Dependent` semantics.** A reader must know that a `@Dependent` bean is instantiated per injection
  to see why three lifecycles get three threads. The annotation is explicit (present on the producer),
  so it is discoverable and look-up-able, the opposite of an implicit default scope; a KDoc on the
  producer and on `PeriodicScheduler` states the isolation intent.
- **Removing `GarbageCollectionExecutor` touches shipped, tested code.** The change is
  behaviour-preserving (the GC lifecycle still schedules on its own single thread), and its test changes
  only the mock type. Accepted because keeping a fourth type would contradict D1.
- **The producer is outside the coverage perimeter.** `SchedulerProducers` lives in `api-application`,
  which is outside the Kover gate by design (composition root, end-to-end tests only). It is exercised
  by the boot integration tests. No perimeter change.
