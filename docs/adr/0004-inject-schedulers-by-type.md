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
2. **Wire the scheduler as a `@Dependent` bean in the composition root.** A `SchedulerProducers` in
   `api-application/wiring/` produces `PeriodicScheduler` via `@Produces @Dependent`, instantiating
   `SingleThreadPeriodicScheduler()`. `@Dependent` is the CDI pseudo-scope that yields a fresh instance
   per injection point, so the three lifecycles obtain three distinct instances (three threads): the
   isolation the qualifiers bought is preserved without a qualifier. `@Dependent` is an explicit
   annotation, so a reader who knows it understands the isolation directly, and one who does not sees
   the annotation and can look it up, the opposite of an implicit default scope.
3. **Keep the lifecycles self-discovered; do not produce them.** The lifecycles stay `@ApplicationScoped`
   with their `@Observes StartupEvent` / `ShutdownEvent`. Producing them was considered and rejected:
   under ArC, a class that declares `@Observes` is discovered as a `@Dependent` class-bean even without a
   scope annotation, so dropping `@ApplicationScoped` does not stop discovery, and the class-bean's
   constructor (taking `PeriodicScheduler`) has no bean to satisfy it. Producing only the scheduler
   avoids that trap and needs no bootstrap.
4. **Enforce the convention with a Konsist test.** `ArchitectureKonsistTest` asserts no production
   source imports `io.smallrye.common.annotation.Identifier`. The convention moves from a paragraph in
   `agents/project.md` to a failing build.
5. **Remove the `GarbageCollectionExecutor` dedicated type.** GC migrates to `PeriodicScheduler` like
   the other two. Keeping it as a fourth type would reintroduce the duplication decision 1 rejects.

## Consequences

- One scheduler type instead of three; the wiring is explicit and centralised in the composition root,
  and the isolation (three schedulers, three threads) is expressed by the `@Dependent` annotation on the
  producer, an explicit marker rather than a string qualifier or an implicit default.
- The inject-by-type convention holds with no "not in scope" caveat and is enforced structurally.
- `GarbageCollectionExecutor` and `SingleThreadGarbageCollectionExecutor` are deleted; the GC lifecycle,
  its former producer in `TaskRuntimeProducers`, and the GC lifecycle test move to `PeriodicScheduler`.
  The change is behaviour-preserving (GC still schedules on its own single thread).
- `SchedulerProducers` sits in `api-application`, outside the Kover perimeter by design; it is exercised
  by the boot integration tests, not by a unit test. No perimeter change.
- The `@Identifier` qualifier is not banned as a Quarkus feature, only in this codebase's production
  sources. A genuine future need suppresses the Konsist assertion inline with a reason, which is the
  point of encoding the convention as a test.
