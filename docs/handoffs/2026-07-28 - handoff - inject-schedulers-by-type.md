# Handoff: inject worker schedulers by type

Branch: `refactor/inject-schedulers-by-type` (PR #37, widened from its docs-only origin; cut from
`main`). Tier: Spec. Spec: `docs/specs/2026-07-27-inject-schedulers-by-type.md`,
ADR: `docs/adr/0004-inject-schedulers-by-type.md`, plan:
`docs/plans/2026-07-27-inject-schedulers-by-type.md`.

## Current state

The last two `@Identifier` string qualifiers are removed from the worker, replaced by a single
`PeriodicScheduler` type injected by type through a `@Dependent` producer in the composition root. A
Konsist test forbids `@Identifier` imports in production, so the inject-by-type convention is now
enforced structurally. The full gate (`./gradlew gate`) is green, 100% branch coverage per package
holds, and `TaskQueueBootIntegrationTest` boots with each lifecycle injecting its own scheduler. The
branch is ready to integrate through a rebased PR.

The branch also carries the two conventions persisted before this sub-project (no abbreviations;
inject-by-type) plus the `./gradlew gate` aggregator and the em-dash pre-commit check; `agents/project.md`
now reads with no "not in scope" caveat and a current inject-by-type example.

## What was built

- `PeriodicScheduler` interface + `SingleThreadPeriodicScheduler` in `api-worker-quarkus` (delegates to
  a single-thread `ScheduledExecutorService`), with a unit test covering both methods.
- The three lifecycles (`TaskWorkerLifecycle`, `ExportRetentionLifecycle`, `GarbageCollectionLifecycle`)
  inject `PeriodicScheduler` by type, no `@Identifier`. They keep `@ApplicationScoped` and their
  `@Observes StartupEvent` / `ShutdownEvent`.
- `SchedulerProducers` in `api-application/wiring/` produces `PeriodicScheduler` via
  `@Produces @Dependent`: one instance per injection point, so each lifecycle gets its own scheduler
  (its own thread). The isolation the qualifiers bought is preserved.
- `ArchitectureKonsistTest` asserts no production source imports
  `io.smallrye.common.annotation.Identifier`.
- Deleted: `GarbageCollectionExecutor` + `SingleThreadGarbageCollectionExecutor` (GC migrates to
  `PeriodicScheduler`), the `TASK_POLL_SCHEDULER` / `EXPORT_PURGE_SCHEDULER` constants, the three
  scheduler producers in `TaskRuntimeProducers`, and `SingleThreadGarbageCollectionExecutorTest` (its
  role is taken by `SingleThreadPeriodicSchedulerTest`).

## Pitfalls / friction

- **The spec's first form (produce the lifecycles) was unreachable in ArC, and the validating spike was
  mis-designed.** An earlier draft had D2/D3 produce the lifecycles (drop `@ApplicationScoped`, keep
  `@Observes`). A spike "confirmed" `@Observes StartupEvent` fires on a produced bean, but the spike
  left the old `@Identifier(TASK_POLL_SCHEDULER) ScheduledExecutorService` constructor resolvable: ArC
  discovered the lifecycle as a `@Dependent` class-bean via its observer methods (independently of the
  producer, which was dead code), and that class-bean's constructor resolved. In T3 the constructor took
  `PeriodicScheduler` (no bean), so the class-bean was unsatisfiable and the build failed. Lesson: a
  spike must isolate the variable it tests (here, remove every other resolution path). Resolved by
  revising to `@Dependent` wiring: the lifecycles stay `@ApplicationScoped`, only the scheduler is
  produced, and each lifecycle gets its own instance per injection.
- **No test directly covers `GarbageCollectionLifecycle` or `ExportRetentionLifecycle`.** Every
  `@QuarkusTest` boots them (a missing bean would fail the boot with `UnsatisfiedDependencyException`),
  but the three-distinct-instances property is the CDI `@Dependent` guarantee, not an asserted test
  outcome. The spec says so (revised after the holistic review).
- **`agents/project.md` example drift.** The inject-by-type convention used `GarbageCollectionExecutor`
  as its example; that type was deleted by T3. T4 refreshed it to `PeriodicScheduler`, `WorkerExecutor`.
- **Plan review caught a missed test deletion.** T3's first draft forgot
  `SingleThreadGarbageCollectionExecutorTest.kt`, which would have broken `compileTestKotlin` after the
  type deletion. The fresh plan review caught it before dispatch.

## Not validated against real conditions

- The full gate is green on this host, including the `api-application` integration tests that boot
  Quarkus and resolve the full CDI graph (so `SchedulerProducers` and the lifecycle wiring are
  exercised). The multi-arch container image build behind `validate / build-image` is not covered by
  any local command.
- The three-threads isolation relies on CDI `@Dependent` semantics (one instance per injection point),
  not on an asserted test. It is a specification guarantee, verified by reasoning and by the green boot,
  not by a test that counts threads.

## Suggested next step

- Integrate: push and update PR #37, merge with `gh pr merge --rebase` (squash is disabled on this repo).
- Then run Improve: the spike-design failure is the main candidate (a spike must isolate the variable it
  tests).

## Improve input (failures the gate did not catch)

- **A mis-designed spike produced a false-positive validation.** The spike "confirmed" `@Observes` fires
  on a produced bean, but it had not removed the old resolution path, so the bean was discovered as a
  class-bean independently and the producer was dead code. The gate cannot catch a bad spike (it is
  throwaway, never merged); the remedy is a judgement call: a spike isolates the variable it tests, with
  every other resolution path removed. Candidate home: `agents/project.md`, or proposed to the baseline.
- **Stale KDoc references survived the refactor until human review.** `PeriodicScheduler.kt`'s KDoc
  referenced `WorkerLifecycleProducers` (deleted) after the `@Dependent` revision, and the GC lifecycle
  KDoc kept the "DB" abbreviation. Neither fails the gate (they are text). The fresh task review and the
  holistic review caught both. Candidate remedy: a Konsist assertion that `[X]` KDoc references resolve
  to a type in scope, but that is broad; the cheaper form is review vigilance on rewritten KDocs.
