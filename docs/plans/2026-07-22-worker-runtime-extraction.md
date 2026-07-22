# Worker Runtime Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the task-worker driving runtime out of `api-presentation-quarkus` into a new `api-worker-quarkus` module that depends on `api-usecases` + `api-domain` only, with no behaviour change.

**Architecture:** Two commits. First decouple `PinDownloadTaskHandler` from the presentation-owned `ImagesConfig` by making it a plain class produced at the composition root (everything stays put, gate stays green). Then perform the pure module move. See `docs/specs/2026-07-22-worker-runtime-extraction.md`.

**Tech Stack:** Kotlin, Quarkus 3 (ArC/CDI, `@ConfigMapping`, `StartupEvent`/`ShutdownEvent`), Micrometer, Gradle (jandex plugin for bean indexing), JUnit 5 + MockK.

## Global Constraints

- **100% branch coverage per package** (`koverVerify`), gated in CI. Add tests for both sides of every conditional.
- **Strict TDD:** failing test first, watch it fail, then minimal implementation.
- **Clean / Hexagonal:** `api-worker-quarkus` depends on `api-usecases` + `api-domain` **only** — never on `api-presentation-quarkus`.
- **Conventional commits** (`refactor(...)`, `test:`, ...).
- **Language: English** for all identifiers and prose.
- **Build/gate under JDK 25** (the operator made `25-tem` the sdkman default): `./gradlew build`.
- **Merges are rebase-only** on this repo.

---

### Task 1: Decouple `PinDownloadTaskHandler` from `ImagesConfig`

Make the handler a plain class taking `maxBytes`/`maxPixels`, produced by a new composition-root producer that reads `ImagesConfig`. Everything stays in its current module; this removes the worker subsystem's only presentation coupling ahead of the move.

**Files:**
- Modify: `api-presentation-quarkus/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/presentation/quarkus/tasks/PinDownloadTaskHandlerTest.kt`
- Modify: `api-presentation-quarkus/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/presentation/quarkus/tasks/PinDownloadTaskHandler.kt`
- Create: `api-application/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/application/wiring/TaskHandlerProducers.kt`

**Interfaces:**
- Consumes: `DownloadPinImage.download(pinId: UUID, context: TaskContext, maxBytes: Long, maxPixels: Long)` (`api-usecases`); `ImagesConfig.maxFileBytes(): Long`, `ImagesConfig.maxPixels(): Long`; `TaskHandler` port (`api-usecases.tasks`).
- Produces: `PinDownloadTaskHandler(downloadPinImage: DownloadPinImage, maxBytes: Long, maxPixels: Long)` — the new constructor Task 2 moves unchanged.

- [ ] **Step 1: Rewrite the failing test** (construct with two `Long`s, drop the `ImagesConfig` mock)

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.usecases.DownloadPinImage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.PinDownloadTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskContext
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class PinDownloadTaskHandlerTest {
    private val downloadPinImage: DownloadPinImage = mockk(relaxed = true)
    private val handler = PinDownloadTaskHandler(downloadPinImage, maxBytes = 100, maxPixels = 200)

    @Test fun `Given the handler, Then its kind is pin download`() {
        assertEquals(PinDownloadTask.KIND, handler.kind)
    }

    @Test fun `Given a pinId payload, Then it delegates with the configured limits`() {
        val pinId = randomUUID()
        handler.handle(pinId.toString(), TaskContext(1, 5))
        verify { downloadPinImage.download(pinId, TaskContext(1, 5), 100, 200) }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :api-presentation-quarkus:compileTestKotlin`
Expected: FAIL — `Too many arguments` / constructor mismatch (handler still takes `ImagesConfig`).

- [ ] **Step 3: Rewrite the handler** (plain class, two longs, no `@ApplicationScoped`, no `ImagesConfig`)

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.usecases.DownloadPinImage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.PinDownloadTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskContext
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskHandler
import java.util.UUID

class PinDownloadTaskHandler(
    private val downloadPinImage: DownloadPinImage,
    private val maxBytes: Long,
    private val maxPixels: Long,
) : TaskHandler {
    override val kind = PinDownloadTask.KIND

    override fun handle(payload: String, context: TaskContext) {
        downloadPinImage.download(
            pinId = UUID.fromString(payload),
            context = context,
            maxBytes = maxBytes,
            maxPixels = maxPixels,
        )
    }
}
```

- [ ] **Step 4: Create the composition-root producer**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.application.wiring

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ImagesConfig
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks.PinDownloadTaskHandler
import fr.geoffreyCoulaud.pinryReborn.api.usecases.DownloadPinImage
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * CDI wiring for [PinDownloadTaskHandler], hosted in the composition root because it needs the
 * `images.*` limits from [ImagesConfig] (owned by the presentation layer) which the worker module
 * must not depend on. The produced bean is collected by the worker's `TaskHandlerRegistry` via
 * `Instance<TaskHandler>`. Companion to [ImageAdapterProducers].
 */
@ApplicationScoped
class TaskHandlerProducers {
    @Produces
    @ApplicationScoped
    fun pinDownloadTaskHandler(
        downloadPinImage: DownloadPinImage,
        imagesConfig: ImagesConfig,
    ): PinDownloadTaskHandler =
        PinDownloadTaskHandler(downloadPinImage, imagesConfig.maxFileBytes(), imagesConfig.maxPixels())
}
```

- [ ] **Step 5: Run the full gate to verify green**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. In particular `PinDownloadTaskHandlerTest` passes, and the `api-application` integration suite (which drives a real pin.download task through the queue) still processes it — proving the produced handler is registered and receives the correct limits.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(tasks): decouple PinDownloadTaskHandler from ImagesConfig via a composition-root producer"
```

---

### Task 2: Extract the worker runtime into `api-worker-quarkus`

Pure move: create the module and relocate the eight production files + six tests, then rewire `api-application` and trim presentation. No behaviour change.

**Files:**
- Create: `api-worker-quarkus/build.gradle.kts`
- Modify: `settings.gradle.kts` (add `include(":api-worker-quarkus")`)
- Move (git mv, then rewrite `package` line to `fr.geoffreyCoulaud.pinryReborn.api.worker`):
  - main: `TaskDispatcher.kt`, `TaskWorkerLifecycle.kt`, `TaskRuntimeProducers.kt`, `WorkerExecutor.kt`, `TaskQueueConfig.kt`, `TaskQueueMetrics.kt`, `PinDownloadTaskHandler.kt`, `AccountDeletionTaskHandler.kt`
  - test: `TaskDispatcherTest.kt`, `TaskWorkerLifecycleTest.kt`, `TaskQueueMetricsTest.kt`, `BoundedWorkerExecutorTest.kt`, `PinDownloadTaskHandlerTest.kt`, `AccountDeletionTaskHandlerTest.kt`
- Modify: `api-application/build.gradle.kts` (add `implementation(project(":api-worker-quarkus"))`)
- Modify: `api-application/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/application/wiring/TaskHandlerProducers.kt` (import path of `PinDownloadTaskHandler`)
- Modify: `api-application/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/application/TaskQueueBootIntegrationTest.kt` (KDoc FQN)
- Modify: `api-presentation-quarkus/build.gradle.kts` (drop worker-only Quarkus deps)

**Interfaces:**
- Consumes: everything from Task 1 plus the eight files' existing dependencies (`api-usecases` tasks/use cases, `api-domain` repositories/tasks/time, Quarkus CDI + `StartupEvent`/`ShutdownEvent` + Micrometer).
- Produces: package `fr.geoffreyCoulaud.pinryReborn.api.worker` containing `PinDownloadTaskHandler`, `TaskWorkerLifecycle`, `TaskQueueMetrics`, etc.

- [ ] **Step 1: Create the module build file** `api-worker-quarkus/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.jandex)
}

allOpen {
    annotation("jakarta.enterprise.context.ApplicationScoped")
}

dependencies {
    implementation(project(":api-domain"))
    implementation(project(":api-usecases"))
    implementation(libs.kotlin.logging)
    implementation(libs.smallrye.config)

    compileOnly(platform(libs.quarkus.bom))
    compileOnly(libs.jakarta.cdi.api)
    compileOnly(libs.quarkus.core)
    compileOnly(libs.quarkus.micrometer)

    testImplementation(testFixtures(project(":api-utilities")))
    testImplementation(platform(libs.quarkus.bom))
    testImplementation(libs.jakarta.cdi.api)
    testImplementation(libs.quarkus.core)
    testImplementation(libs.quarkus.micrometer)
    testImplementation(libs.bundles.testing)
    testRuntimeOnly(libs.bundles.testing.runtime)
}
```

- [ ] **Step 2: Register the module** — add to `settings.gradle.kts` after `include(":api-system")`

```kotlin
include(":api-worker-quarkus")
```

- [ ] **Step 3: Move the files and rewrite their package line**

```bash
BASE=api-worker-quarkus
MAIN="$BASE/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/worker"
TEST="$BASE/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/worker"
mkdir -p "$MAIN" "$TEST"
SRC=api-presentation-quarkus/src
P=fr/geoffreyCoulaud/pinryReborn/api/presentation/quarkus/tasks
for f in TaskDispatcher TaskWorkerLifecycle TaskRuntimeProducers WorkerExecutor TaskQueueConfig TaskQueueMetrics PinDownloadTaskHandler AccountDeletionTaskHandler; do
  git mv "$SRC/main/kotlin/$P/$f.kt" "$MAIN/$f.kt"
done
for f in TaskDispatcherTest TaskWorkerLifecycleTest TaskQueueMetricsTest BoundedWorkerExecutorTest PinDownloadTaskHandlerTest AccountDeletionTaskHandlerTest; do
  git mv "$SRC/test/kotlin/$P/$f.kt" "$TEST/$f.kt"
done
NEWPKG="package fr.geoffreyCoulaud.pinryReborn.api.worker"
find "$MAIN" "$TEST" -name '*.kt' -exec sed -i "1s|^package .*|$NEWPKG|" {} +
grep -rL "^package fr.geoffreyCoulaud.pinryReborn.api.worker$" "$MAIN" "$TEST"   # expect: no output
```

The moved files reference each other only within the same package, so no cross-file import edits are needed.

- [ ] **Step 4: Rewire `api-application`**

Add to `api-application/build.gradle.kts`, after `implementation(project(":api-system"))`:

```kotlin
    implementation(project(":api-worker-quarkus"))
```

In `TaskHandlerProducers.kt`, change the `PinDownloadTaskHandler` import from
`fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks.PinDownloadTaskHandler` to:

```kotlin
import fr.geoffreyCoulaud.pinryReborn.api.worker.PinDownloadTaskHandler
```

In `TaskQueueBootIntegrationTest.kt`, update the KDoc reference (documentation only) from
`fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks.TaskWorkerLifecycle` to
`fr.geoffreyCoulaud.pinryReborn.api.worker.TaskWorkerLifecycle`.

- [ ] **Step 5: Trim presentation build deps** in `api-presentation-quarkus/build.gradle.kts`

Remove the four worker-only Quarkus dependencies (no presentation file outside `tasks/` imports `io.quarkus.runtime.*` or Micrometer — verified):

```
// main
    compileOnly(libs.quarkus.core)        // remove
    compileOnly(libs.quarkus.micrometer)  // remove
// test
    testImplementation(libs.quarkus.core)        // remove (was: unit-test TaskWorkerLifecycle)
    testImplementation(libs.quarkus.micrometer)  // remove (was: unit-test TaskQueueMetrics)
```

Also delete the two now-stale explanatory comments above the removed test deps.

- [ ] **Step 6: Run the full gate to verify green**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. Confirms: the new module compiles + passes `koverVerify`; presentation still compiles without the trimmed deps; and the `api-application` integration suite is green — `TaskQueueBootIntegrationTest` (worker boots and settles a task to DEAD), the download-from-URL mode-B test (`PinDownloadTaskHandler` via the queue), and the account-deletion end-to-end test — proving CDI discovery, lifecycle observers, producers, and the poll loop all work from the new module.

- [ ] **Step 7: Verify the layering invariant**

Run: `grep -rn "presentation.quarkus" api-worker-quarkus/src`
Expected: no output (the worker module has zero reference to presentation).

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor(worker): extract the task-worker runtime into the api-worker-quarkus module"
```
