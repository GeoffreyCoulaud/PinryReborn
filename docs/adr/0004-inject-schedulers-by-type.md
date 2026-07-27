# 0004. Inject worker schedulers by type

Status: Proposed
Date: 2026-07-27

## Context

The inject-by-type convention (decided 2026-07-27, persisted in `agents/project.md`) says a dependency
is a dedicated type that carries its role, and the container provides the instance; `@Identifier("...")`
string qualifiers are not used for new code, because they couple a consumer to a producer's name rather
than to its type.

When the convention was adopted, the garbage collection scheduler had just been migrated to a dedicated
type (`GarbageCollectionExecutor`), but two older schedulers were left on `@Identifier` qualifiers:
`TASK_POLL_SCHEDULER` and `EXPORT_PURGE_SCHEDULER`, both on a raw JDK `ScheduledExecutorService`. The
convention recorded them as "not in scope". They are the only remaining `@Identifier` uses in the
codebase (verified by search).

The qualifiers existed for a concrete reason, documented in the `TASK_POLL_SCHEDULER` KDoc: Quarkus's
ArC container always registers a synthetic `@Default` bean for the raw `ScheduledExecutorService` /
`ExecutorService` / `Executor` types, backed by its main blocking pool. Without a qualifier, two beans
of that raw type would be ambiguous as soon as anything injected it. The qualifier was the workaround
for injecting a JDK type that ArC also claims; it was never a deliberate design choice to identify a
role by string.

## Decision

1. **Collapse the three schedulers onto one interface and one implementation.** `PeriodicScheduler`
   (`scheduleWithFixedDelay` + `shutdown`) and `SingleThreadPeriodicScheduler` replace the two qualified
   `ScheduledExecutorService` beans and the dedicated `GarbageCollectionExecutor`. The three schedulers
   expose the same surface and each is an isolated single thread, so a dedicated type per scheduler
   would triplicate an identical contract for no gain. The role is carried by the wiring (which
   lifecycle consumes which instance), not by the type.
2. **Wire the scheduler explicitly in the composition root.** A `WorkerLifecycleProducers` in
   `api-application/wiring/` produces the three lifecycles, and each producer instantiates
   `SingleThreadPeriodicScheduler()` and passes it to the constructor. The isolation (one thread per
   role) is stated in plain code a reader can follow without CDI knowledge. This rejects an implicit
   `@Dependent` producer, whose "instance per injection" semantics are invisible at the call site.
3. **Produce the lifecycles; their `@Observes` methods still fire under ArC.** Producing a lifecycle
   means the class loses its `@ApplicationScoped` annotation, which raised whether ArC still discovers
   and calls its `@Observes StartupEvent` / `ShutdownEvent`. Validated empirically: a spike produced
   `TaskWorkerLifecycle` via `@Produces @ApplicationScoped`, and `TaskQueueBootIntegrationTest` still
   passed (the enqueued task settled to DEAD), proving the poller started and the `@Observes
   StartupEvent` fired on the produced bean.
4. **Enforce the convention with a Konsist test.** `ArchitectureKonsistTest` asserts no production
   source imports `io.smallrye.common.annotation.Identifier`. The convention moves from a paragraph in
   `agents/project.md` to a failing build.
5. **Remove the `GarbageCollectionExecutor` dedicated type.** GC migrates to `PeriodicScheduler` like
   the other two. Keeping it as a fourth type would reintroduce the duplication decision 1 rejects.

## Consequences

- One scheduler type instead of three; the wiring is explicit and centralised in the composition root,
  and the isolation (three schedulers, three threads) is visible as three
  `SingleThreadPeriodicScheduler()` instantiations rather than encoded in `@Dependent` semantics or in
  string qualifiers.
- The inject-by-type convention holds with no "not in scope" caveat and is enforced structurally.
- `GarbageCollectionExecutor` and `SingleThreadGarbageCollectionExecutor` are deleted; the GC lifecycle,
  its former producer in `TaskRuntimeProducers`, and the GC lifecycle test move to `PeriodicScheduler`.
  The change is behaviour-preserving (GC still schedules on its own single thread).
- The three lifecycles are no longer self-discovered `@ApplicationScoped` beans; they are produced from
  `api-application/wiring/`. Validated by spike that `@Observes StartupEvent` / `ShutdownEvent` still
  fire, but this is a property worth re-checking if Quarkus changes how producer-method beans are
  processed.
- `WorkerLifecycleProducers` sits in `api-application`, outside the Kover perimeter by design; it is
  exercised by the boot integration tests, not by a unit test. No perimeter change.
- The `@Identifier` qualifier is not banned as a Quarkus feature, only in this codebase's production
  sources. A genuine future need suppresses the Konsist assertion inline with a reason, which is the
  point of encoding the convention as a test.
