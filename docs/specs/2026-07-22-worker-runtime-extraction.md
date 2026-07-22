# Spec — Extract the task-worker runtime into `api-worker-quarkus`

**Date:** 2026-07-22
**Status:** proposed (awaiting operator approval)
**Campaign:** architecture cleanup, item **C** (of A → B → C → D). See
`docs/handoffs/2026-07-22 - handoff - architecture-cleanup-campaign.md`.

## Context

An entire task-worker **driving** subsystem currently lives inside the HTTP presentation module,
`api-presentation-quarkus/.../tasks/`. Tasks are a driving adapter (a background poller that pulls work
and invokes use cases), not HTTP presentation. This is the architecture correction the backlog has
flagged since 2026-07-21.

The eight production files and their true dependencies:

| File | Depends on |
|---|---|
| `TaskDispatcher` (+ `TASK_POLL_SCHEDULER` const) | `api-usecases` (`TaskProcessor`), `api-domain` (`TaskQueueInterface`, `Clock`), Quarkus CDI |
| `TaskWorkerLifecycle` | `api-usecases` (`ReapExpiredTasks`), `quarkus-core` (`StartupEvent`/`ShutdownEvent`), smallrye `@Identifier`, CDI |
| `TaskRuntimeProducers` | `api-usecases` (`TaskHandler`, `TaskHandlerRegistry`), `api-domain` (`BackoffPolicy`, `ExponentialBackoffWithJitter`), CDI |
| `WorkerExecutor` (+ `BoundedWorkerExecutor`) | JDK only |
| `TaskQueueConfig` | smallrye-config (`@ConfigMapping(prefix = "tasks")`) |
| `TaskQueueMetrics` | `api-domain` (`TaskQueueInterface`, `TaskState`), `quarkus-micrometer`, `quarkus-core` (`StartupEvent`), CDI |
| `AccountDeletionTaskHandler` | `api-usecases` (`AccountDeletionCleaner`, `AccountDeletionTask`, `TaskHandler`), CDI |
| `PinDownloadTaskHandler` | `api-usecases` (`DownloadPinImage`, `PinDownloadTask`, `TaskHandler`) **and `ImagesConfig` (presentation)** |

Everything is `api-usecases` / `api-domain` + Quarkus runtime + JDK, **except one coupling**:
`PinDownloadTaskHandler` reads `ImagesConfig` (a `@ConfigMapping(prefix = "images")` interface that
lives in `api-presentation-quarkus/config`). This is the only reference from the whole `tasks/` subtree
(main and test) into presentation. A worker module cannot depend on presentation, so this coupling must
be resolved.

`TaskProcessor`, the `TaskHandler` port, `TaskHandlerRegistry`, and `EnqueueTask` / `CancelTask` /
`ReapExpiredTasks` are correctly in `api-usecases` and stay. `SystemClock` (the `Clock` impl) already
lives in `api-system` (campaign item B) and does **not** move here.

## Goal

Move the worker runtime into a new `api-worker-quarkus` module that depends on `api-usecases` +
`api-domain` only, mirroring the per-adapter-module convention (`api-storage-filesystem`,
`api-imaging-vips`, `api-fetch-http`, `api-system`). No behaviour change; the async flows (pin image
download, account deletion) keep working end to end.

### In scope

- New Gradle module `api-worker-quarkus`.
- Move the eight production files + their six tests into it.
- Resolve the `ImagesConfig` coupling (see Design).
- Wire `api-application` to the new module; trim presentation build deps that only the worker used.

### Out of scope

- No new task features, no queue-semantics change, no DB migration.
- Task-worker observability (DEAD/failed logging) and the deleted-account residue GC stay as separate
  P2 backlog items.
- **Option 3** (promote `maxFileBytes` / `maxPixels` into a use-case-level image-limits policy injected
  into both `ImageController` and `PinDownloadTaskHandler`, removing the duplicated config read) is a
  reasonable future cleanup but is deliberately **not** done here. Noted as a future backlog item.

## Design

### The `ImagesConfig` coupling — Option 2b (produce the handler at the composition root)

`PinDownloadTaskHandler` stops depending on `ImagesConfig`. Its constructor takes the two plain values
it actually needs:

```kotlin
// Plain class — NOT @ApplicationScoped (it is produced, not discovered; see below)
class PinDownloadTaskHandler(
    private val downloadPinImage: DownloadPinImage,
    private val maxBytes: Long,
    private val maxPixels: Long,
) : TaskHandler {
    override val kind = PinDownloadTask.KIND
    override fun handle(payload: String, context: TaskContext) {
        downloadPinImage.download(pinId = UUID.fromString(payload), context = context,
            maxBytes = maxBytes, maxPixels = maxPixels)
    }
}
```

Because it now needs values that only the composition root can read from `ImagesConfig`, it can no
longer be a self-contained discovered bean: its `@ApplicationScoped` annotation is **dropped** (a
discovered bean would fail — `Long` is not injectable, and it must not be both discovered and produced).
`api-application` produces it instead, next to the existing
`ImageAdapterProducers` (which already reads `ImagesConfig`), in a small new wiring producer:

```kotlin
// api-application/.../wiring/TaskHandlerProducers.kt
@ApplicationScoped
class TaskHandlerProducers {
    @Produces
    @ApplicationScoped
    fun pinDownloadTaskHandler(downloadPinImage: DownloadPinImage, config: ImagesConfig): PinDownloadTaskHandler =
        PinDownloadTaskHandler(downloadPinImage, config.maxFileBytes(), config.maxPixels())
}
```

The produced bean is still collected by `TaskRuntimeProducers.taskHandlerRegistry(handlers: Instance<TaskHandler>)`
(CDI resolves producer-method beans by type). `AccountDeletionTaskHandler` depends only on
`api-usecases`, so it stays a discovered `@ApplicationScoped` bean inside the worker module.

Result: the worker module has zero dependency on presentation and zero config-mapping of its own for
`images.*`; the one cross-cutting read lands in the composition root, which is its job. This is the
graph campaign item D (Konsist) will bless.

### New module `api-worker-quarkus`

`build.gradle.kts` (proposed; exact dep set finalised by compilation):

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.jandex)
}
allOpen { annotation("jakarta.enterprise.context.ApplicationScoped") }
dependencies {
    implementation(project(":api-domain"))
    implementation(project(":api-usecases"))
    implementation(libs.kotlin.logging)
    implementation(libs.smallrye.config)            // @ConfigMapping (TaskQueueConfig); brings smallrye @Identifier

    compileOnly(platform(libs.quarkus.bom))
    compileOnly(libs.jakarta.cdi.api)               // @ApplicationScoped/@Produces/@Observes/Instance
    compileOnly(libs.quarkus.core)                  // StartupEvent/ShutdownEvent
    compileOnly(libs.quarkus.micrometer)            // MeterRegistry/Gauge (TaskQueueMetrics)

    testImplementation(testFixtures(project(":api-utilities")))
    testImplementation(platform(libs.quarkus.bom))
    testImplementation(libs.jakarta.cdi.api)
    testImplementation(libs.quarkus.core)           // TaskWorkerLifecycleTest (Startup/ShutdownEvent)
    testImplementation(libs.quarkus.micrometer)     // TaskQueueMetricsTest (SimpleMeterRegistry)
    testImplementation(libs.bundles.testing)
    testRuntimeOnly(libs.bundles.testing.runtime)
}
```

Notes:
- The `jandex` plugin is required so Quarkus indexes the module's beans (same invariant as `api-system`).
- `allOpen` on `@ApplicationScoped` lets Quarkus subclass/proxy the beans (presentation needs it too).
- The worker does **not** reuse `libs.bundles.quarkus.compileOnly` (that bundle is REST-centric:
  resteasy/jax-rs/jackson) — it needs none of that.
- `settings.gradle.kts` gains `include(":api-worker-quarkus")`.

### `api-application` changes

- `implementation(project(":api-worker-quarkus"))` (CDI discovery of the worker beans).
- New `wiring/TaskHandlerProducers.kt` producing `PinDownloadTaskHandler` (above).
- `TaskQueueBootIntegrationTest` references `...tasks.TaskWorkerLifecycle` only inside a KDoc comment;
  update that fully-qualified name to the new package (documentation only, not a compile dependency).
- The runtime `MeterRegistry` bean stays provided by `quarkus-micrometer-registry-prometheus` (already
  an app dependency), so `TaskQueueMetrics` gauges keep registering.

### `api-presentation-quarkus` changes

- Delete the `tasks/` package (main + test) — moved out.
- Trim build deps that only the worker used, verified by compilation: `micrometer` and lifecycle events
  are used in presentation **only** under `tasks/` (confirmed by grep), so drop `compileOnly(libs.quarkus.micrometer)`
  and the two test-only deps added "to unit-test TaskWorkerLifecycle / TaskQueueMetrics"
  (`testImplementation(libs.quarkus.core)`, `testImplementation(libs.quarkus.micrometer)`). Re-check
  `compileOnly(libs.quarkus.core)`: keep it only if something outside `tasks/` still needs it.
- Presentation controllers enqueue via `EnqueueTask` (use case), never referencing the worker runtime,
  so nothing in presentation breaks.

### Package

New package `fr.geoffreyCoulaud.pinryReborn.api.worker` for all moved files (main + test). Moves use
`git mv` to preserve history, then a one-line package change per file (as in item B).

## Testing (TDD)

This is mostly a **move**: the six moved tests are the spec and must stay green throughout. One genuine
change is `PinDownloadTaskHandler`'s constructor, done test-first:

1. **Red:** update `PinDownloadTaskHandlerTest` to construct the handler with two `Long`s
   (`maxBytes`, `maxPixels`) instead of a mocked `ImagesConfig`, asserting `download` is invoked with
   those values. Fails to compile against the current constructor.
2. **Green:** change the handler constructor to take the two longs.

After this, `PinDownloadTaskHandlerTest` no longer imports `ImagesConfig`, so the worker test source set
has zero presentation dependency.

The other moved tests (`TaskDispatcherTest`, `TaskWorkerLifecycleTest`, `TaskQueueMetricsTest`,
`BoundedWorkerExecutorTest`, `AccountDeletionTaskHandlerTest`) move unchanged apart from their package
line.

## Verification

Full gate under JDK 25 (all module tests + `koverVerify` 100% branch per package + detekt). The
extraction's real proof is the `api-application` integration suite, unchanged and green:

- `TaskQueueBootIntegrationTest` — real Quarkus boot + real poller/worker pool settles an enqueued task
  to DEAD. Proves the worker module's lifecycle observers, producers, and poll loop are discovered and
  wired from their new home.
- The download-from-URL (mode-B) image test — exercises `PinDownloadTaskHandler` through the queue,
  proving the composition-root producer supplies the handler with the correct `maxBytes` / `maxPixels`.
- The account-deletion end-to-end test — exercises `AccountDeletionTaskHandler`.

## Risks / watch-items

- **CDI bean discovery** for the new module — mitigated by the `jandex` plugin; validated by
  `TaskQueueBootIntegrationTest`. (Same invariant that worked for `api-system`.)
- **`@Identifier(TASK_POLL_SCHEDULER)` qualifier** disambiguates `TaskRuntimeProducers.pollScheduler`
  from Quarkus's synthetic `ScheduledExecutorService` bean. It moves intact with `TaskDispatcher.kt`.
- **`allOpen` coverage** of `@ApplicationScoped` in the new module — without it Quarkus cannot proxy the
  beans; included in the build.
- **Producer bean type** — `TaskHandlerProducers` returns the concrete `PinDownloadTaskHandler`; CDI
  still exposes `TaskHandler` as a bean type, so `Instance<TaskHandler>` collects it.

## Migration / config

None. No entity change → no Ebean migration. `tasks.*` config keeps its `@ConfigMapping(prefix = "tasks")`
defaults; `application.properties` sets no `tasks.*` overrides today, so behaviour is identical.
