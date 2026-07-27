# Plan: inject worker schedulers by type

Date: 2026-07-27
Spec: `docs/specs/2026-07-27-inject-schedulers-by-type.md`
ADR: `docs/adr/0004-inject-schedulers-by-type.md`
Branch: `refactor/inject-schedulers-by-type`

## Conventions for every task

- **TDD, red first.** Each behavioural task commits the failing test alone as
  `test(scope): <behaviour>` before any implementation. The task reviewer sees only the red commit as
  red evidence. T3 is a behaviour-preserving refactor: it is exempt from the red-first order, and the
  existing lifecycle tests plus the Konsist test (T1) are its safety net.
- **Scope.** Touch only the files a task lists. Adjacent defects go to the backlog, not this branch.
- **Coverage.** New code is inside the gate perimeter; 100% branch per package. The
  `models`/`models.bases` and Ebean `Q*`/`@io.ebean.typequery.Generated` exclusions still hold.
- **Gate.** `./gradlew gate` runs once at Verify, not per task; each task's own module tests must pass
  before the next task begins. The full gate is RED from T1 until T3, because the Konsist test (T1)
  fails until the `@Identifier` imports are gone (T3); per-task verification uses the task's own module
  test, not the full gate, in that window.

## Ordering and dependencies

T1 (Konsist red) and T2 (the new scheduler type) are independent. T3 (the wiring refactor) depends on
T2 (it consumes `PeriodicScheduler`) and turns T1 green. T4 (the doc caveat) is independent and lands
last, after the debt is resolved. Default dispatch is serial, branch-in-place, in the order T1, T2,
T3, T4.

The new type lives at `api-worker-quarkus/.../PeriodicScheduler.kt`, next to the lifecycles that
consume it (where `GarbageCollectionExecutor.kt` stood).

---

### T1: Konsist test forbidding `@Identifier` (red)

The structural enforcement of the inject-by-type convention. Lands red on purpose.

**Depends on:** none.
**Files:**
- `api-application/src/test/kotlin/.../ArchitectureKonsistTest.kt` (add one test, following the file's
  filter-to-offenders-then-`assertEmpty` pattern). Add the import
  `com.lemonappdev.konsist.api.ext.list.withName`; the file imports `withoutName` today, not `withName`:
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
**Commit:** `test(architecture): forbid @Identifier string qualifier import`
**Acceptance:** the new test FAILS red today: `TaskRuntimeProducers.kt`, `ExportRetentionLifecycle.kt`
and `TaskWorkerLifecycle.kt` still import `Identifier`. Show the red.
**Verify:** `./gradlew :api-application:test --tests "*ArchitectureKonsistTest"` fails on the new test.

### T2: `PeriodicScheduler` interface, implementation and unit test (green, isolated)

The single scheduler type all three lifecycles will consume.

**Depends on:** none.
**Files:**
- `api-worker-quarkus/src/main/kotlin/.../PeriodicScheduler.kt` (new): the `PeriodicScheduler`
  interface (`scheduleWithFixedDelay`, `shutdown`) and `SingleThreadPeriodicScheduler`, delegating to
  `Executors.newSingleThreadScheduledExecutor()`, exactly as in spec §4.1.
- `api-worker-quarkus/src/test/kotlin/.../SingleThreadPeriodicSchedulerTest.kt` (new).
**Tests (red first):**
- `Given a scheduled task, Then it runs on the executor's thread`: `scheduleWithFixedDelay` runs a
  task that counts down a `CountDownLatch`; `await` it with a timeout and assert it reached zero
  (proves delegation to the real executor).
- `Given shutdown, Then further scheduling is rejected`: after `shutdown()`, a further
  `scheduleWithFixedDelay` throws `RejectedExecutionException` (proves `shutdown` stopped the
  delegate; deterministic, no thread-polling).
**Commits:** `test(worker): cover SingleThreadPeriodicScheduler` (red), then
`feat(worker): add PeriodicScheduler` (green).
**Acceptance:** both methods 100% branch-covered; detekt clean.
**Verify:** `./gradlew :api-worker-quarkus:test --tests "*SingleThreadPeriodicSchedulerTest"` green;
`:api-worker-quarkus:detekt` clean; `:api-worker-quarkus:koverVerify` green.

### T3: Wire the lifecycles explicitly from the composition root (turns T1 green)

The refactor. Atomic by necessity: removing `@ApplicationScoped` and the qualifier producers must land
together with the new `WorkerLifecycleProducers`, or the worker cannot boot.

**Depends on:** T2.
**Files:**
- `api-worker-quarkus/.../TaskWorkerLifecycle.kt`: scheduler param `ScheduledExecutorService` ->
  `PeriodicScheduler`; drop `@Identifier` and the `TASK_POLL_SCHEDULER` constant; drop
  `@ApplicationScoped` (now produced); keep `@Observes StartupEvent` / `ShutdownEvent` and the body.
- `api-worker-quarkus/.../ExportRetentionLifecycle.kt`: same shape; drop `EXPORT_PURGE_SCHEDULER`.
- `api-worker-quarkus/.../GarbageCollectionLifecycle.kt`: scheduler param `GarbageCollectionExecutor`
  -> `PeriodicScheduler`; drop `@ApplicationScoped`; keep the body and its `@Observes` methods; rewrite
  the class KDoc, which today links `[GarbageCollectionExecutor]` and explains isolation as a dedicated
  type. Reference `PeriodicScheduler` and rephrase: isolation now comes from three
  producer-instantiated `SingleThreadPeriodicScheduler()` calls in `WorkerLifecycleProducers`, one per
  lifecycle, not from a distinct garbage-collection-scoped type.
- delete `api-worker-quarkus/.../GarbageCollectionExecutor.kt` (interface + impl).
- delete `api-worker-quarkus/src/test/kotlin/.../SingleThreadGarbageCollectionExecutorTest.kt`: it
  covered the deleted wrapper's two delegation methods, a role now taken by T2's
  `SingleThreadPeriodicSchedulerTest`. Leaving it breaks `compileTestKotlin` (the symbols are gone) and
  the plan's atomicity claim.
- `api-worker-quarkus/.../TaskRuntimeProducers.kt`: delete the three scheduler producers
  (`pollScheduler`, `exportPurgeScheduler`, `garbageCollectionExecutor`) and the now-unused
  `Identifier` / `ScheduledExecutorService` imports; keep `Executors` (still used by `workerExecutor`)
  and the other producers.
- `api-application/src/main/kotlin/.../wiring/WorkerLifecycleProducers.kt` (new): three
  `@Produces @ApplicationScoped` methods, each instantiating `SingleThreadPeriodicScheduler()` and
  passing it to the lifecycle constructor, with the lifecycle's other dependencies injected as producer
  params. Exactly as in spec §4.3.
- `api-worker-quarkus/src/test/.../TaskWorkerLifecycleTest.kt`,
  `ExportRetentionLifecycleTest.kt`, `GarbageCollectionLifecycleTest.kt`: change the scheduler mock
  type to `PeriodicScheduler` (from `ScheduledExecutorService` / `GarbageCollectionExecutor`
  respectively). No assertion changes: the lifecycles call the same two methods.
**Commit:** `refactor(worker): inject schedulers by type from the composition root`
**Acceptance:**
- The three lifecycles receive `PeriodicScheduler`; no `@Identifier` anywhere in production.
- `GarbageCollectionExecutor` / `SingleThreadGarbageCollectionExecutor` gone.
- `TaskQueueBootIntegrationTest` still passes (proves the produced lifecycles boot and the poller
  starts; the integration check the spike relied on).
- The Konsist test from T1 now passes.
- `./gradlew gate` green, 100% branch per package.
**Verify:** `./gradlew gate` green; `rg "@Identifier"` over `**/src/main/**` empty;
`rg "TASK_POLL_SCHEDULER|EXPORT_PURGE_SCHEDULER"` empty; `:api-application:test` green.

### T4: Drop the resolved caveat from `agents/project.md`

**Depends on:** none (documentation; logically lands after T3 resolves the debt).
**Files:**
- `agents/project.md`: delete the sentence "The pre-existing `TASK_POLL_SCHEDULER` /
  `EXPORT_PURGE_SCHEDULER` schedulers predate this rule and are not in scope." from the inject-by-type
  invariant (Design invariants). No other change.
**Commit:** `docs(agents): drop resolved scheduler qualifier debt caveat`
**Acceptance:** the inject-by-type invariant reads with no "not in scope" caveat; the diff is that one
sentence.
**Verify:** diff shows only the sentence removed.

---

## After T4

Verify runs the full gate (`./gradlew gate`) and a holistic review by a fresh subagent over the branch
diff. Wrap reconciles `docs/backlog.md` (the periodic-maintenance item added in Discuss stays open; no
item closes here), writes the handoff, and integrates via a rebased PR. Improve records any rule the
gate should have caught.
