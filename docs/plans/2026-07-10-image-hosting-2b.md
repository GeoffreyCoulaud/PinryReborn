# Image Hosting (2b — Server-Side Ingestion / Mode B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a pin's canonical image be fetched by the server from a source URL, asynchronously, via the background task queue, with a diagnosable failure experience, reusing 2a's storage/probe/persistence.

**Architecture:** Clean/Hexagonal. `api-domain` gains an `ImageDownload` entity, an `ImageFetcher` port + typed fetch exceptions, an `ImageDownloadRepositoryInterface`, and a `TransactionRunner` port. A new adapter module `api-fetch-http` implements `ImageFetcher` with the JDK HTTP client + a Standard SSRF guard. Use cases orchestrate the fetch (`DownloadPinImage`), the mode-B request (`RequestPinImageDownload`), and the state read (`ResolvePinImageState`). A thin `pin.download` `TaskHandler` bean in the Quarkus layer dispatches the queue task; `PUT /api/v1/pins/{pinId}/image` is content-negotiated (multipart = mode A, JSON = mode B → `202`), and `GET /api/v1/pins/{pinId}/image/status` is the poll target.

**Tech Stack:** Kotlin 2.4, Quarkus 3.37 (RESTEasy Reactive), Ebean 19 + SQLite, JDK `java.net.http.HttpClient` (no new dependency), JUnit 5 + MockK + REST Assured + `com.sun.net.httpserver.HttpServer` (test origin). JDK 25 toolchain, Java 25 bytecode.

## Global Constraints

- **100% branch coverage per package** (Kover gate), for every module except `api-application`. The new `api-fetch-http` module is in-gate. Exercise both sides of every conditional.
- **Strict TDD**: write the failing test first, watch it fail, then the minimal implementation.
- **Clean/Hexagonal purity**: `api-domain` is pure (no I/O, CDI, Ebean, logging). `api-usecases` depends on `api-domain` only (no I/O, no serialization lib). Adapters do I/O. `api-fetch-http` depends on `api-domain` only. Presentation depends on `api-usecases` + `api-domain`. The composition root (`api-application`) wires adapters.
- **English everywhere** (identifiers, comments, commit messages, docs).
- **Conventional commits** (`feat(domain):`, `feat(persistence):`, `feat(fetch):`, `feat(usecase):`, `test:`, `chore:`).
- **No top-level functions** outside a class/object; extension functions are the only exception. (Constants live in an `object`.)
- **No em-dashes** in user-facing copy (the `DownloadReason` messages). Use plain sentences.
- **`taskId` is a `UUID`** (the queue's `Task.id` is `UUID`, not the INTEGER rowid the old queue spec assumed). Every `taskId` field / column / parameter is `UUID` / `uuid`.
- **Payload is the bare `pinId` string** (`pinId.toString()`), never JSON: the handler reads the `sourceUrl` from the `image_download` row, so no serialization dependency enters `api-usecases`/the handler.
- **CAS on `status = PENDING`**: every `image_download` write that must be idempotent under at-least-once (markFailed, recordLastError, deleteIfPending) is guarded `WHERE status = 'PENDING'`. The success swap deletes the row only if still PENDING.
- **Atomicity via `TransactionRunner`**: request-time (upsert download + enqueue) and the success swap (save image + delete download) each run inside one transaction; `enqueue` and the image repositories join the ambient transaction (`database.currentTransaction()`).
- **SSRF Standard guard in the adapter**: scheme allowlist (`http`/`https`) + reject loopback/private/link-local/reserved/metadata IPs, on the initial URL and every redirect hop; redirect cap. DNS-rebinding is a consciously accepted gap.
- **Download timeout < lease**: `connect_timeout + request_timeout` must stay under `tasks.lease_duration` (default `PT1M`); defaults `PT5S + PT30S = 35s`.
- Spec: `docs/specs/2026-07-10-image-hosting-2b.md`.

---

## File Structure

**New files:**

- `api-domain/.../domain/entities/ImageDownload.kt` — sidecar entity (keyed by `pinId`).
- `api-domain/.../domain/enums/DownloadStatus.kt`, `.../enums/DownloadReason.kt`.
- `api-domain/.../domain/repositories/ImageDownloadRepositoryInterface.kt`.
- `api-domain/.../domain/repositories/TransactionRunner.kt` — atomic-unit-of-work port.
- `api-domain/.../domain/images/ImageFetcher.kt` — server-side fetch port.
- `api-domain/.../domain/images/FetchException.kt` — sealed fetch-failure family.
- `api-persistence-sqlite/.../models/ImageDownloadModel.kt` + `.../mappers/ImageDownloadModelMapper.kt` + `.../repositories/EbeanImageDownloadRepository.kt`.
- `api-persistence-sqlite/.../repositories/EbeanTransactionRunner.kt`.
- `api-persistence-sqlite/src/main/resources/dbmigration/1.5.sql` (+ `model/1.5.model.xml`) — `image_download` table.
- `api-fetch-http/` (new module) — `build.gradle.kts`, `HttpImageFetcher.kt`, `AddressPolicy.kt`.
- `api-usecases/.../usecases/DownloadPinImage.kt`, `RequestPinImageDownload.kt`, `ResolvePinImageState.kt`, `PinImageState.kt`, `ClearPinDownload.kt`.
- `api-usecases/.../usecases/tasks/TaskContext.kt`, `.../tasks/PinDownloadTask.kt`.
- `api-presentation-quarkus/.../config/ImageDownloadConfig.kt`.
- `api-presentation-quarkus/.../tasks/PinDownloadTaskHandler.kt`.
- `api-presentation-quarkus/.../dtos/output/PinImageStateDto.kt` + `.../mappers/PinImageStateMapper.kt`.
- `api-application/.../wiring/FetchAdapterProducers.kt` — `ImageFetcher` producer.

**Modified files:**

- `settings.gradle.kts` — include `:api-fetch-http`.
- `api-application/build.gradle.kts` — depend on `:api-fetch-http`.
- `api-application/src/main/resources/application.properties` — `images.download.*` defaults (optional, documented).
- `api-usecases/.../tasks/TaskHandler.kt` — `handle(payload, context)`.
- `api-usecases/.../tasks/TaskProcessor.kt` — build + pass `TaskContext`.
- `api-usecases/.../SetPinImage.kt`, `.../DeletePinImage.kt` — clear the download via `ClearPinDownload`.
- `api-usecases/.../PinRecycleBin.kt` — cascade: cancel download + drop row.
- `api-usecases/.../exceptions/ErrorCode.kt` + `.../exceptions/ImageError.kt` — `IMAGE_SOURCE_URL_INVALID`.
- `api-persistence-sqlite/.../repositories/EbeanTaskQueue.kt` — ambient-transaction-aware `enqueue`.
- `api-persistence-sqlite/.../repositories/EbeanImageRepository.kt` — ambient-transaction-aware `save`.
- `api-presentation-quarkus/.../controllers/ImageController.kt` — mode-B `PUT` (JSON) + `GET .../image/status`.
- `api-presentation-quarkus/.../mappers/BaseErrorMapper.kt` — map `IMAGE_SOURCE_URL_INVALID` → 400.
- The queue tests (`TaskProcessorTest`, `TaskHandlerRegistryTest`, and any integration handler) — new `handle` signature.

---

## Phase 0 — Scaffolding

### Task 1: `api-fetch-http` module + `ImageDownloadConfig`

**Files:**
- Modify: `settings.gradle.kts`
- Create: `api-fetch-http/build.gradle.kts`
- Modify: `api-application/build.gradle.kts`
- Create: `api-presentation-quarkus/.../config/ImageDownloadConfig.kt`
- Modify: `api-application/src/main/resources/application.properties`
- Test: `api-presentation-quarkus/.../config/ImageDownloadConfigTest.kt`

**Interfaces:**
- Produces: a buildable `:api-fetch-http` module depending on `:api-domain`; `ImageDownloadConfig` with `connectTimeout(): Duration`, `requestTimeout(): Duration`, `maxRedirects(): Int`, `allowPrivateAddresses(): Boolean`.

- [ ] **Step 1: Include the module in settings.** In `settings.gradle.kts`, after `include(":api-imaging-vips")` add:
```kotlin
include(":api-fetch-http")
```

- [ ] **Step 2: Write `api-fetch-http/build.gradle.kts`** (mirrors `api-storage-filesystem`; JDK HttpClient needs no dependency):
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jandex)
}
dependencies {
    implementation(project(":api-domain"))
    compileOnly(libs.jakarta.cdi.api)
    testImplementation(testFixtures(project(":api-utilities")))
    testImplementation(libs.bundles.testing)
    testRuntimeOnly(libs.bundles.testing.runtime)
}
```

- [ ] **Step 3: Wire the adapter into the composition root.** In `api-application/build.gradle.kts` `dependencies`, add:
```kotlin
    implementation(project(":api-fetch-http"))
```

- [ ] **Step 4: Write the failing config test** (`ImageDownloadConfig` is an interface; test an anonymous impl to lock the shape):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class ImageDownloadConfigTest {
    @Test
    fun `Given a config implementation, Then its accessors are readable`() {
        // Given
        val config = object : ImageDownloadConfig {
            override fun connectTimeout() = Duration.ofSeconds(5)
            override fun requestTimeout() = Duration.ofSeconds(30)
            override fun maxRedirects() = 5
            override fun allowPrivateAddresses() = false
        }
        // Then
        assertEquals(Duration.ofSeconds(5), config.connectTimeout())
        assertEquals(Duration.ofSeconds(30), config.requestTimeout())
        assertEquals(5, config.maxRedirects())
        assertEquals(false, config.allowPrivateAddresses())
    }
}
```

- [ ] **Step 5: Run to fail.** `./gradlew :api-presentation-quarkus:test --tests "*ImageDownloadConfigTest" --console=plain` → FAIL (`ImageDownloadConfig` not defined).

- [ ] **Step 6: Implement `ImageDownloadConfig`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

@ConfigMapping(prefix = "images.download", namingStrategy = ConfigMapping.NamingStrategy.SNAKE_CASE)
interface ImageDownloadConfig {
    @WithDefault("PT5S")
    fun connectTimeout(): Duration

    @WithDefault("PT30S")
    fun requestTimeout(): Duration

    @WithDefault("5")
    fun maxRedirects(): Int

    // Escape hatch for trusted networks (e.g. a self-hoster pinning from a LAN NAS) and for
    // integration tests that fetch from a loopback origin. Default false = full Standard SSRF guard.
    @WithDefault("false")
    fun allowPrivateAddresses(): Boolean
}
```

- [ ] **Step 7: Document the defaults** in `api-application/src/main/resources/application.properties` (append; `@WithDefault` already covers runtime, this is for visibility):
```properties
# Server-side image download (mode B). request_timeout + connect_timeout must stay < tasks.lease_duration.
images.download.connect_timeout=PT5S
images.download.request_timeout=PT30S
images.download.max_redirects=5
# Only enable on trusted networks: allows fetching from private/loopback addresses (disables the SSRF guard).
images.download.allow_private_addresses=false
```

- [ ] **Step 8: Verify the graph builds + the test passes.** Run: `./gradlew :api-fetch-http:compileKotlin :api-presentation-quarkus:test --tests "*ImageDownloadConfigTest" --console=plain` → BUILD SUCCESSFUL.

- [ ] **Step 9: Commit.**
```bash
git add settings.gradle.kts api-fetch-http api-application/build.gradle.kts api-application/src/main/resources/application.properties api-presentation-quarkus/src/main/kotlin api-presentation-quarkus/src/test/kotlin
git commit -m "chore(fetch): scaffold api-fetch-http module and images.download config"
```

## Phase 1 — Queue contract extension

### Task 2: `TaskContext` + `TaskHandler.handle(payload, context)`

**Files:**
- Create: `api-usecases/.../usecases/tasks/TaskContext.kt`
- Modify: `api-usecases/.../usecases/tasks/TaskHandler.kt`
- Modify: `api-usecases/.../usecases/tasks/TaskProcessor.kt`
- Modify: `api-usecases/.../usecases/tasks/TaskProcessorTest.kt` (and any other `TaskHandler` impl in tests)

**Interfaces:**
- Consumes: `ClaimedTask.attempts`, `ClaimedTask.maxAttempts`.
- Produces: `TaskContext(attempt: Int, maxAttempts: Int)`; `TaskHandler.handle(payload: String, context: TaskContext)`.

- [ ] **Step 1: Update the failing test first.** In `TaskProcessorTest.kt`, change the `handler` helper's `handle` signature (the compile break is the "failing test"):
```kotlin
    private fun handler(kind: String, body: () -> Unit) = object : TaskHandler {
        override val kind = kind
        override fun handle(payload: String, context: TaskContext) = body()
    }
```
Add one test asserting the context is passed with the claimed attempt/maxAttempts:
```kotlin
    @Test
    fun `Given a handler, Then it receives the claim's attempt and maxAttempts`() {
        // Given
        every { clock.now() } returns now
        var seen: TaskContext? = null
        val c = claimed(attempts = 2, maxAttempts = 3)
        val p = processorWith(object : TaskHandler {
            override val kind = "k"
            override fun handle(payload: String, context: TaskContext) { seen = context }
        })
        // When
        p.execute(c)
        // Then
        assertEquals(TaskContext(2, 3), seen)
    }
```
(Add `import org.junit.jupiter.api.Assertions.assertEquals` if absent.)

- [ ] **Step 2: Run to fail.** `./gradlew :api-usecases:test --tests "*TaskProcessorTest" --console=plain` → FAIL to compile (`TaskContext` / new signature).

- [ ] **Step 3: Create `TaskContext`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

/** Per-attempt context handed to a [TaskHandler]: the current attempt number and the task's budget. */
data class TaskContext(val attempt: Int, val maxAttempts: Int)
```

- [ ] **Step 4: Update `TaskHandler`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

interface TaskHandler {
    val kind: String
    fun handle(payload: String, context: TaskContext)
}
```

- [ ] **Step 5: Update `TaskProcessor`.** In `execute`, change the dispatch line to build + pass the context, and change `runHandler`'s signature/body:
```kotlin
            val outcome = runHandler(handler, claimed.payload, TaskContext(claimed.attempts, claimed.maxAttempts))
```
```kotlin
    @Suppress("TooGenericExceptionCaught")
    private fun runHandler(handler: TaskHandler, payload: String, context: TaskContext): Outcome =
        try {
            handler.handle(payload, context)
            Success
        } catch (e: PermanentTaskException) {
            Permanent(e.reason)
        } catch (e: Exception) {
            Retryable(e.message ?: "transient failure")
        }
```

- [ ] **Step 6: Update any other test `TaskHandler` impls.** Run `git grep -n "override fun handle(payload"` and update every implementation to the two-arg signature (e.g. `TaskHandlerRegistryTest`, and any handler in `api-application` integration tests). Each becomes `override fun handle(payload: String, context: TaskContext)`.

- [ ] **Step 7: Run the queue tests.** `./gradlew :api-usecases:test --tests "*TaskProcessorTest" --tests "*TaskHandlerRegistryTest" --console=plain` → PASS.

- [ ] **Step 8: Commit.**
```bash
git add api-usecases/src
git commit -m "feat(tasks): pass per-attempt TaskContext into TaskHandler.handle"
```

## Phase 2 — Domain (pure)

### Task 3: `ImageDownload` + enums + `ImageDownloadRepositoryInterface` + `TransactionRunner`

**Files:**
- Create: `api-domain/.../domain/entities/ImageDownload.kt`
- Create: `api-domain/.../domain/enums/DownloadStatus.kt`, `.../enums/DownloadReason.kt`
- Create: `api-domain/.../domain/repositories/ImageDownloadRepositoryInterface.kt`
- Create: `api-domain/.../domain/repositories/TransactionRunner.kt`

**Interfaces:**
- Produces: `ImageDownload(pinId, sourceUrl, status, reasonCode, lastError, taskId, requestedAt, updatedAt)`; `DownloadStatus{PENDING,FAILED}`; `DownloadReason{...9...}`; `ImageDownloadRepositoryInterface`; `TransactionRunner`.

These are pure declarations (no branches to test); their coverage comes from the persistence and use-case tasks that consume them. Add them, then confirm the module compiles.

- [ ] **Step 1: `DownloadStatus`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.enums

enum class DownloadStatus { PENDING, FAILED }
```

- [ ] **Step 2: `DownloadReason`** (the failure taxonomy; user-facing copy lives in the presentation mapper, not here):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.enums

enum class DownloadReason {
    URL_NOT_ALLOWED,
    UNREACHABLE,
    ACCESS_DENIED,
    NOT_FOUND,
    TOO_LARGE,
    INVALID_IMAGE,
    TOO_MANY_PIXELS,
    INTERNAL_ERROR,
    FETCH_FAILED,
}
```

- [ ] **Step 3: `ImageDownload`** (keyed by `pinId`; not `Identifiable` — its identity is the pin):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import java.time.Instant
import java.util.UUID

data class ImageDownload(
    val pinId: UUID,
    val sourceUrl: String,
    val status: DownloadStatus,
    val reasonCode: DownloadReason?,
    val lastError: String?,
    val taskId: UUID,
    val requestedAt: Instant,
    val updatedAt: Instant,
)
```

- [ ] **Step 4: `ImageDownloadRepositoryInterface`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.ImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import java.time.Instant
import java.util.UUID

interface ImageDownloadRepositoryInterface {
    /** Create-or-replace the pin's download row as PENDING with a fresh sourceUrl + taskId. */
    fun upsertPending(pinId: UUID, sourceUrl: String, taskId: UUID, now: Instant): ImageDownload

    fun findByPinId(pinId: UUID): ImageDownload?

    /** CAS on PENDING: set FAILED + reason. Returns true if a PENDING row was updated. */
    fun markFailed(pinId: UUID, reason: DownloadReason, now: Instant): Boolean

    /** CAS on PENDING: record the last transient error, keep PENDING. Returns true if updated. */
    fun recordLastError(pinId: UUID, lastError: String, now: Instant): Boolean

    /** CAS on PENDING: delete the row only if still PENDING. Returns the number of rows deleted (0 or 1). */
    fun deleteIfPending(pinId: UUID): Int

    /** Unconditional delete of the pin's download row (idempotent). */
    fun deleteByPinId(pinId: UUID)
}
```

- [ ] **Step 5: `TransactionRunner`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

/**
 * Runs [block] inside a single persistence transaction. Repository calls and task enqueues issued
 * from within [block] join that ambient transaction, so multi-write operations commit atomically.
 * The domain declares the port; the persistence adapter implements it (no Ebean leaks into callers).
 */
interface TransactionRunner {
    fun <T> inTransaction(block: () -> T): T
}
```

- [ ] **Step 6: Compile.** `./gradlew :api-domain:compileKotlin --console=plain` → BUILD SUCCESSFUL.

- [ ] **Step 7: Commit.**
```bash
git add api-domain/src
git commit -m "feat(domain): ImageDownload entity, download enums, download + transaction ports"
```

### Task 4: `ImageFetcher` port + `FetchException` family

**Files:**
- Create: `api-domain/.../domain/images/ImageFetcher.kt`
- Create: `api-domain/.../domain/images/FetchException.kt`

**Interfaces:**
- Produces: `ImageFetcher.openStream(sourceUrl: String): InputStream`; sealed `FetchException` with `UrlNotAllowedException`, `FetchAccessDeniedException`, `FetchNotFoundException`, `FetchTooLargeException`, `TooManyRedirectsException`, `FetchFailedException`, `FetchUnreachableException`.

- [ ] **Step 1: `ImageFetcher`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.images

import java.io.InputStream

interface ImageFetcher {
    /**
     * Apply the scheme allowlist + per-hop SSRF checks, follow redirects (capped), require a 2xx
     * response, and return the body stream for staging. Throws a typed [FetchException] on any
     * failure. Does not read/validate image content (that is [ImageProbe]'s job). The caller owns
     * closing the returned stream.
     */
    fun openStream(sourceUrl: String): InputStream
}
```

- [ ] **Step 2: `FetchException` family:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.images

/** Base for failures raised while fetching image bytes from a source URL (mode B). */
sealed class FetchException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** URL blocked by policy: disallowed scheme, malformed, or a private/loopback/reserved address. */
class UrlNotAllowedException(message: String, cause: Throwable? = null) : FetchException(message, cause)

/** The origin refused server access (HTTP 401/403) — the bounce case. */
class FetchAccessDeniedException(message: String, cause: Throwable? = null) : FetchException(message, cause)

/** The origin returned 404/410. */
class FetchNotFoundException(message: String, cause: Throwable? = null) : FetchException(message, cause)

/** The body exceeded the configured size (declared Content-Length or streamed). */
class FetchTooLargeException(message: String, cause: Throwable? = null) : FetchException(message, cause)

/** Too many redirect hops. */
class TooManyRedirectsException(message: String, cause: Throwable? = null) : FetchException(message, cause)

/** Any other permanent HTTP failure (e.g. an unexpected 4xx). */
class FetchFailedException(message: String, cause: Throwable? = null) : FetchException(message, cause)

/** Transient reachability failure: DNS, connect refused, timeout, TLS, 5xx, 429. Retryable. */
class FetchUnreachableException(message: String, cause: Throwable? = null) : FetchException(message, cause)
```

- [ ] **Step 3: Compile.** `./gradlew :api-domain:compileKotlin --console=plain` → BUILD SUCCESSFUL.

- [ ] **Step 4: Commit.**
```bash
git add api-domain/src
git commit -m "feat(domain): ImageFetcher port and typed fetch exceptions"
```

## Phase 3 — Persistence

### Task 5: `ImageDownloadModel` + mapper + migration 1.5

**Files:**
- Create: `api-persistence-sqlite/.../models/ImageDownloadModel.kt`
- Create: `api-persistence-sqlite/.../mappers/ImageDownloadModelMapper.kt`
- Create: `api-persistence-sqlite/src/main/resources/dbmigration/1.5.sql` + `model/1.5.model.xml`
- Test: `api-persistence-sqlite/.../mappers/ImageDownloadModelMapperTest.kt`

**Interfaces:**
- Produces: `ImageDownloadModel` (`image_download` table, `@Id var pinId`), `ImageDownloadModelMapper.toDomain()/toModel()`.

- [ ] **Step 1: Write the failing mapper test** (cover both the FAILED-with-reason and the PENDING-null-reason branches of the mapper's `reasonCode?.let { ... }`):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.ImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.ImageDownloadModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.ImageDownloadModelMapper.toModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class ImageDownloadModelMapperTest {
    @Test
    fun `Given a PENDING download, Then toModel and toDomain round-trip its fields`() {
        val download = ImageDownload(
            pinId = randomUUID(), sourceUrl = "https://x/i.png", status = DownloadStatus.PENDING,
            reasonCode = null, lastError = null, taskId = randomUUID(),
            requestedAt = Instant.parse("2026-07-10T00:00:00Z"), updatedAt = Instant.parse("2026-07-10T00:00:01Z"),
        )
        assertEquals(download, download.toModel().toDomain())
    }

    @Test
    fun `Given a FAILED download with a reason, Then it round-trips the reason`() {
        val download = ImageDownload(
            pinId = randomUUID(), sourceUrl = "https://x/i.png", status = DownloadStatus.FAILED,
            reasonCode = DownloadReason.ACCESS_DENIED, lastError = "403", taskId = randomUUID(),
            requestedAt = Instant.parse("2026-07-10T00:00:00Z"), updatedAt = Instant.parse("2026-07-10T00:00:02Z"),
        )
        assertEquals(download, download.toModel().toDomain())
    }
}
```

- [ ] **Step 2: Run to fail.** `./gradlew :api-persistence-sqlite:test --tests "*ImageDownloadModelMapperTest" --console=plain` → FAIL.

- [ ] **Step 3: Implement `ImageDownloadModel`** (standalone entity, `pinId` as the primary key):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "image_download")
class ImageDownloadModel(
    @Id var pinId: UUID,
    var sourceUrl: String,
    var status: String,
    var reasonCode: String?,
    var lastError: String?,
    var taskId: UUID,
    var requestedAt: Instant,
    var updatedAt: Instant,
)
```

- [ ] **Step 4: Implement `ImageDownloadModelMapper`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.ImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.ImageDownloadModel

object ImageDownloadModelMapper {
    fun ImageDownload.toModel() = ImageDownloadModel(
        pinId = pinId, sourceUrl = sourceUrl, status = status.name, reasonCode = reasonCode?.name,
        lastError = lastError, taskId = taskId, requestedAt = requestedAt, updatedAt = updatedAt,
    )

    fun ImageDownloadModel.toDomain() = ImageDownload(
        pinId = pinId, sourceUrl = sourceUrl, status = DownloadStatus.valueOf(status),
        reasonCode = reasonCode?.let { DownloadReason.valueOf(it) }, lastError = lastError,
        taskId = taskId, requestedAt = requestedAt, updatedAt = updatedAt,
    )
}
```

- [ ] **Step 5: Run the mapper test.** Expected: PASS.

- [ ] **Step 6: Generate the migration.** Run: `./gradlew :api-persistence-sqlite:generateDbMigration --console=plain`. This emits `dbmigration/1.5.sql` + `model/1.5.model.xml` for the new `image_download` table.

- [ ] **Step 7: Verify `1.5.sql`** matches the following (the generator should produce this create-table; hand-adjust if it differs — no FK, since the project's `foreign_keys` pragma is off and every FK is decorative; the `pin_id` PK enforces one download per pin):
```sql
-- apply changes
create table image_download (
  pin_id                        uuid not null,
  source_url                    text not null,
  status                        text not null,
  reason_code                   text,
  last_error                    text,
  task_id                       uuid not null,
  requested_at                  timestamp not null,
  updated_at                    timestamp not null,
  constraint pk_image_download primary key (pin_id)
);
```
Keep `model/1.5.model.xml` as the generator produced it (a `createTable name="image_download"` changeSet with `pin_id` as the primary key column).

- [ ] **Step 8: Verify migrations bootstrap.** `./gradlew :api-persistence-sqlite:test --tests "*ImageDownloadModelMapperTest" --console=plain` again — the in-memory DB now applies `1.5`. Expected: PASS (no bootstrap error).

- [ ] **Step 9: Commit.**
```bash
git add -A
git commit -m "feat(persistence): add image_download table (migration 1.5), model and mapper"
```

### Task 6: `EbeanImageDownloadRepository`

**Files:**
- Create: `api-persistence-sqlite/.../repositories/EbeanImageDownloadRepository.kt`
- Test: `api-persistence-sqlite/src/test/kotlin/.../EbeanImageDownloadRepositoryTest.kt`

**Interfaces:**
- Consumes: `ImageDownloadRepositoryInterface`, `ImageDownload`, `QImageDownloadModel(database)` (kapt-generated once `ImageDownloadModel` exists).
- Produces: `@ApplicationScoped class EbeanImageDownloadRepository(database: Database) : ImageDownloadRepositoryInterface`. Methods issue plain `database`/`QImageDownloadModel` calls with NO explicit `beginTransaction`, so they auto-join the ambient transaction (from `TransactionRunner`) when one is active, else run in their own auto-commit.

- [ ] **Step 1: Write the failing test** (extends `RepositoryTest`; no pin FK is needed since FKs are decorative, but use a random `pinId`). Cover: upsert then find; upsert replaces (FAILED → PENDING resets reason); `markFailed` updates a PENDING row and no-ops a missing/non-PENDING row; `recordLastError` CAS; `deleteIfPending` returns 1 for PENDING and 0 for FAILED/missing; `deleteByPinId` idempotent.
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.EbeanImageDownloadRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class EbeanImageDownloadRepositoryTest : RepositoryTest() {
    private val repository = EbeanImageDownloadRepository(database)
    private val now = Instant.parse("2026-07-10T00:00:00Z")

    @Test
    fun `Given upsertPending, Then findByPinId returns a PENDING row`() {
        val pinId = randomUUID()
        val saved = repository.upsertPending(pinId, "https://x/i.png", randomUUID(), now)
        assertEquals(DownloadStatus.PENDING, saved.status)
        assertEquals(saved, repository.findByPinId(pinId))
    }

    @Test
    fun `Given an existing row, Then upsertPending replaces it with a fresh PENDING`() {
        val pinId = randomUUID()
        repository.upsertPending(pinId, "https://x/a.png", randomUUID(), now)
        repository.markFailed(pinId, DownloadReason.NOT_FOUND, now)
        val replaced = repository.upsertPending(pinId, "https://x/b.png", randomUUID(), now)
        assertEquals("https://x/b.png", replaced.sourceUrl)
        assertEquals(DownloadStatus.PENDING, repository.findByPinId(pinId)?.status)
        assertNull(repository.findByPinId(pinId)?.reasonCode)
    }

    @Test
    fun `Given a PENDING row, Then markFailed sets FAILED and returns true`() {
        val pinId = randomUUID()
        repository.upsertPending(pinId, "https://x/i.png", randomUUID(), now)
        assertTrue(repository.markFailed(pinId, DownloadReason.ACCESS_DENIED, now))
        val row = repository.findByPinId(pinId)
        assertEquals(DownloadStatus.FAILED, row?.status)
        assertEquals(DownloadReason.ACCESS_DENIED, row?.reasonCode)
    }

    @Test
    fun `Given no PENDING row, Then markFailed returns false`() {
        assertFalse(repository.markFailed(randomUUID(), DownloadReason.ACCESS_DENIED, now))
    }

    @Test
    fun `Given a PENDING row, Then recordLastError keeps PENDING and returns true`() {
        val pinId = randomUUID()
        repository.upsertPending(pinId, "https://x/i.png", randomUUID(), now)
        assertTrue(repository.recordLastError(pinId, "timeout", now))
        assertEquals(DownloadStatus.PENDING, repository.findByPinId(pinId)?.status)
        assertEquals("timeout", repository.findByPinId(pinId)?.lastError)
    }

    @Test
    fun `Given no PENDING row, Then recordLastError returns false`() {
        assertFalse(repository.recordLastError(randomUUID(), "x", now))
    }

    @Test
    fun `Given a PENDING row, Then deleteIfPending deletes it and returns 1`() {
        val pinId = randomUUID()
        repository.upsertPending(pinId, "https://x/i.png", randomUUID(), now)
        assertEquals(1, repository.deleteIfPending(pinId))
        assertNull(repository.findByPinId(pinId))
    }

    @Test
    fun `Given a FAILED row, Then deleteIfPending returns 0 and keeps the row`() {
        val pinId = randomUUID()
        repository.upsertPending(pinId, "https://x/i.png", randomUUID(), now)
        repository.markFailed(pinId, DownloadReason.NOT_FOUND, now)
        assertEquals(0, repository.deleteIfPending(pinId))
        assertEquals(DownloadStatus.FAILED, repository.findByPinId(pinId)?.status)
    }

    @Test
    fun `Given any row, Then deleteByPinId removes it and is a no-op when absent`() {
        val pinId = randomUUID()
        repository.upsertPending(pinId, "https://x/i.png", randomUUID(), now)
        repository.deleteByPinId(pinId)
        assertNull(repository.findByPinId(pinId))
        repository.deleteByPinId(randomUUID()) // must not throw
    }
}
```

- [ ] **Step 2: Run to fail.** `./gradlew :api-persistence-sqlite:test --tests "*EbeanImageDownloadRepositoryTest" --console=plain` → FAIL.

- [ ] **Step 3: Implement `EbeanImageDownloadRepository`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.ImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageDownloadRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.ImageDownloadModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.ImageDownloadModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.ImageDownloadModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QImageDownloadModel
import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class EbeanImageDownloadRepository(
    private val database: Database,
) : ImageDownloadRepositoryInterface {
    // No explicit beginTransaction here: delete+save and the bulk CAS updates run under the ambient
    // transaction when TransactionRunner opened one (Ebean binds it to the thread), else auto-commit.
    override fun upsertPending(pinId: UUID, sourceUrl: String, taskId: UUID, now: Instant): ImageDownload {
        QImageDownloadModel(database).pinId.equalTo(pinId).delete()
        val model = ImageDownload(
            pinId = pinId, sourceUrl = sourceUrl, status = DownloadStatus.PENDING, reasonCode = null,
            lastError = null, taskId = taskId, requestedAt = now, updatedAt = now,
        ).toModel()
        database.save(model)
        return model.toDomain()
    }

    override fun findByPinId(pinId: UUID): ImageDownload? =
        QImageDownloadModel(database).pinId.equalTo(pinId).findOne()?.toDomain()

    override fun markFailed(pinId: UUID, reason: DownloadReason, now: Instant): Boolean =
        pendingRows(pinId)
            .asUpdate()
            .set("status", DownloadStatus.FAILED.name)
            .set("reasonCode", reason.name)
            .set("updatedAt", now)
            .update() > 0

    override fun recordLastError(pinId: UUID, lastError: String, now: Instant): Boolean =
        pendingRows(pinId)
            .asUpdate()
            .set("lastError", lastError)
            .set("updatedAt", now)
            .update() > 0

    override fun deleteIfPending(pinId: UUID): Int = pendingRows(pinId).delete()

    override fun deleteByPinId(pinId: UUID) {
        QImageDownloadModel(database).pinId.equalTo(pinId).delete()
    }

    private fun pendingRows(pinId: UUID) =
        QImageDownloadModel(database).pinId.equalTo(pinId).status.equalTo(DownloadStatus.PENDING.name)
}
```

- [ ] **Step 4: Run the test.** Expected: PASS (all cases).

- [ ] **Step 5: Commit.**
```bash
git add api-persistence-sqlite/src
git commit -m "feat(persistence): EbeanImageDownloadRepository with CAS-on-PENDING writes"
```

### Task 7: `EbeanTransactionRunner` + ambient-transaction-aware `enqueue` and image `save` (outbox)

**Files:**
- Create: `api-persistence-sqlite/.../repositories/EbeanTransactionRunner.kt`
- Modify: `api-persistence-sqlite/.../repositories/EbeanTaskQueue.kt` (`enqueue` only)
- Modify: `api-persistence-sqlite/.../repositories/EbeanImageRepository.kt` (`save` only)
- Test: `api-persistence-sqlite/src/test/kotlin/.../EbeanTransactionRunnerTest.kt`

**Interfaces:**
- Produces: `@ApplicationScoped class EbeanTransactionRunner(database) : TransactionRunner`; `enqueue`/`save` that join `database.currentTransaction()` when one is active.

- [ ] **Step 1: Write the failing test** (extends `RepositoryTest`; uses the real `EnqueueTask` + `EbeanImageDownloadRepository` to prove atomic rollback = neither row, and commit = both rows):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.EbeanImageDownloadRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.EbeanTaskQueue
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.EbeanTransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class EbeanTransactionRunnerTest : RepositoryTest() {
    private val runner: TransactionRunner = EbeanTransactionRunner(database)
    private val queue = EbeanTaskQueue(database)
    private val downloads = EbeanImageDownloadRepository(database)
    private val now = Instant.parse("2026-07-10T00:00:00Z")

    @Test
    fun `Given a committed transaction, Then the enqueued task and the download row both exist`() {
        val pinId = randomUUID()
        val taskId = runner.inTransaction {
            val task = queue.enqueue(newDownloadTask(pinId))
            downloads.upsertPending(pinId, "https://x/i.png", task.id, now)
            task.id
        }
        assertNotNull(queue.findById(taskId))
        assertNotNull(downloads.findByPinId(pinId))
    }

    @Test
    fun `Given a rolled-back transaction, Then neither the task nor the download row exists`() {
        val pinId = randomUUID()
        assertThrows(IllegalStateException::class.java) {
            runner.inTransaction {
                val task = queue.enqueue(newDownloadTask(pinId))
                downloads.upsertPending(pinId, "https://x/i.png", task.id, now)
                throw IllegalStateException("boom")
            }
        }
        assertNull(downloads.findByPinId(pinId))
        assertEquals(0, queue.countByState(TaskState.PENDING))
    }

    // newDownloadTask(pinId): a NewTask(kind="pin.download", payload=pinId.toString(), availableAt=now,
    // maxAttempts=5) — inline helper; import NewTask from the domain.tasks package.
}
```
(Provide the `newDownloadTask` helper inline: `NewTask("pin.download", pinId.toString(), now, maxAttempts = 5)` with `import ...domain.tasks.NewTask`.)

- [ ] **Step 2: Run to fail.** `./gradlew :api-persistence-sqlite:test --tests "*EbeanTransactionRunnerTest" --console=plain` → FAIL (`EbeanTransactionRunner` missing; and, before the ambient fix, `enqueue` opens its own transaction so the rollback test would leave a task behind).

- [ ] **Step 3: Implement `EbeanTransactionRunner`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class EbeanTransactionRunner(
    private val database: Database,
) : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T =
        database.beginTransaction().use { transaction ->
            val result = block()
            transaction.commit()
            result
        }
}
```

- [ ] **Step 4: Make `EbeanTaskQueue.enqueue` ambient-transaction-aware.** Replace the `enqueue` method body so it joins an active transaction instead of always opening its own (extract the dedup-check + insert into a private `enqueueWithin`):
```kotlin
    override fun enqueue(task: NewTask): Task =
        if (database.currentTransaction() != null) {
            enqueueWithin(task)
        } else {
            database.beginTransaction().use { transaction ->
                val result = enqueueWithin(task)
                transaction.commit()
                result
            }
        }

    private fun enqueueWithin(task: NewTask): Task {
        if (task.dedupKey != null) {
            val existing =
                QTaskModel(database)
                    .dedupKey.equalTo(task.dedupKey)
                    .state.isIn(TaskState.PENDING.name, TaskState.RUNNING.name)
                    .findOne()
            if (existing != null) return existing.toDomain()
        }
        val model =
            TaskModel(
                id = randomUUID(),
                kind = task.kind,
                payload = task.payload,
                state = TaskState.PENDING.name,
                priority = task.priority,
                availableAt = task.availableAt,
                attempts = 0,
                maxAttempts = task.maxAttempts,
                dedupKey = task.dedupKey,
            )
        database.save(model)
        return model.toDomain()
    }
```

- [ ] **Step 5: Make `EbeanImageRepository.save` ambient-transaction-aware** (same pattern; extract `saveWithin`):
```kotlin
    override fun save(image: Image): Image =
        if (database.currentTransaction() != null) {
            saveWithin(image)
        } else {
            database.beginTransaction().use { transaction ->
                val result = saveWithin(image)
                transaction.commit()
                result
            }
        }

    private fun saveWithin(image: Image): Image {
        QImageModel(database).pinId.equalTo(image.pinId).delete()
        val model = image.toModel()
        database.save(model)
        return model.toDomain()
    }
```

- [ ] **Step 6: Run the new test + the existing queue/image repo tests** (the else-branch of `enqueue`/`save` is still covered by the existing `EbeanTaskQueueTest`/`EbeanImageRepositoryTest`; the new test covers the ambient branch):
```bash
./gradlew :api-persistence-sqlite:test --tests "*EbeanTransactionRunnerTest" --tests "*EbeanTaskQueueTest" --tests "*EbeanImageRepositoryTest" --console=plain
```
Expected: PASS.

- [ ] **Step 7: Commit.**
```bash
git add api-persistence-sqlite/src
git commit -m "feat(persistence): TransactionRunner and ambient-transaction-aware enqueue/save (outbox)"
```

## Phase 4 — Fetch adapter

### Task 8: `HttpImageFetcher` + `AddressPolicy` (api-fetch-http)

**Files:**
- Create: `api-fetch-http/.../fetch/http/AddressPolicy.kt`
- Create: `api-fetch-http/.../fetch/http/HttpImageFetcher.kt`
- Test: `api-fetch-http/src/test/kotlin/.../StandardAddressPolicyTest.kt`
- Test: `api-fetch-http/src/test/kotlin/.../HttpImageFetcherTest.kt`

**Interfaces:**
- Consumes: `ImageFetcher`, the `FetchException` family.
- Produces: `AddressPolicy` (interface) + `AddressPolicy.Standard` / `AddressPolicy.AllowAll`; `HttpImageFetcher(connectTimeout, requestTimeout, maxRedirects, addressPolicy)`.

- [ ] **Step 1: Write the failing `StandardAddressPolicy` test** (table over address classes; both allowed and blocked sides):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.fetch.http

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

class StandardAddressPolicyTest {
    private val policy = AddressPolicy.Standard

    @Test fun `Given a public address, Then it is allowed`() =
        assertTrue(policy.isAllowed(InetAddress.getByName("93.184.216.34")))

    @Test fun `Given loopback, Then it is blocked`() =
        assertFalse(policy.isAllowed(InetAddress.getByName("127.0.0.1")))

    @Test fun `Given a private 10 8 address, Then it is blocked`() =
        assertFalse(policy.isAllowed(InetAddress.getByName("10.0.0.1")))

    @Test fun `Given a private 192 168 address, Then it is blocked`() =
        assertFalse(policy.isAllowed(InetAddress.getByName("192.168.1.1")))

    @Test fun `Given link-local metadata 169 254, Then it is blocked`() =
        assertFalse(policy.isAllowed(InetAddress.getByName("169.254.169.254")))

    @Test fun `Given IPv6 loopback, Then it is blocked`() =
        assertFalse(policy.isAllowed(InetAddress.getByName("::1")))

    @Test fun `Given IPv6 unique-local fc00, Then it is blocked`() =
        assertFalse(policy.isAllowed(InetAddress.getByName("fc00::1")))

    @Test fun `Given AllowAll, Then every address is allowed`() =
        assertTrue(AddressPolicy.AllowAll.isAllowed(InetAddress.getByName("127.0.0.1")))
}
```

- [ ] **Step 2: Run to fail.** `./gradlew :api-fetch-http:test --tests "*StandardAddressPolicyTest" --console=plain` → FAIL.

- [ ] **Step 3: Implement `AddressPolicy`** (public interface + two objects; the composition root selects one by config):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.fetch.http

import java.net.Inet6Address
import java.net.InetAddress

/** Decides whether a resolved address may be connected to (the SSRF address filter). */
interface AddressPolicy {
    fun isAllowed(address: InetAddress): Boolean

    /** No filtering: only for trusted networks / tests (config `allow_private_addresses=true`). */
    object AllowAll : AddressPolicy {
        override fun isAllowed(address: InetAddress): Boolean = true
    }

    /** Standard guard: reject loopback, private, link-local (incl. metadata), reserved, and IPv6 ULA. */
    object Standard : AddressPolicy {
        override fun isAllowed(address: InetAddress): Boolean = !isBlocked(address)

        private fun isBlocked(address: InetAddress): Boolean =
            address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                address.isMulticastAddress ||
                isUniqueLocalIpv6(address)

        // fc00::/7 (IPv6 unique-local) is not covered by the standard InetAddress predicates.
        private fun isUniqueLocalIpv6(address: InetAddress): Boolean =
            address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc
    }
}
```

- [ ] **Step 4: Run the policy test.** Expected: PASS.

- [ ] **Step 5: Write the failing `HttpImageFetcher` test** (starts a local `com.sun.net.httpserver.HttpServer` on `127.0.0.1`, uses `AddressPolicy.AllowAll` so the loopback origin is reachable; covers 2xx body, a redirect hop, 403/404/5xx mapping, redirect cap, and a scheme rejection). Sketch of the harness + one case:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.fetch.http

import com.sun.net.httpserver.HttpServer
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchAccessDeniedException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UrlNotAllowedException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration

class HttpImageFetcherTest {
    private lateinit var server: HttpServer
    private val fetcher = HttpImageFetcher(
        connectTimeout = Duration.ofSeconds(2),
        requestTimeout = Duration.ofSeconds(2),
        maxRedirects = 3,
        addressPolicy = AddressPolicy.AllowAll,
    )

    @BeforeEach fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }
    @AfterEach fun stop() = server.stop(0)

    private fun base() = "http://127.0.0.1:${server.address.port}"

    private fun handle(path: String, status: Int, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()) {
        server.createContext(path) { exchange ->
            headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
    }

    @Test
    fun `Given a 200 response, Then openStream returns the body`() {
        val bytes = byteArrayOf(1, 2, 3)
        handle("/i.png", 200, bytes)
        fetcher.openStream("${base()}/i.png").use { assertArrayEquals(bytes, it.readAllBytes()) }
    }

    @Test
    fun `Given a 403 response, Then it throws FetchAccessDenied`() {
        handle("/i.png", 403)
        assertThrows(FetchAccessDeniedException::class.java) { fetcher.openStream("${base()}/i.png") }
    }

    @Test
    fun `Given a file scheme, Then it throws UrlNotAllowed`() {
        assertThrows(UrlNotAllowedException::class.java) { fetcher.openStream("file:///etc/passwd") }
    }

    // Add the remaining cases, each asserting the mapped exception or the followed body:
    //  - 302 to /final (200) → returns /final's body (one redirect hop).
    //  - a redirect chain longer than maxRedirects → TooManyRedirectsException.
    //  - 404 → FetchNotFoundException; 500 → FetchUnreachableException.
    //  - a Standard-policy fetcher against 127.0.0.1 → UrlNotAllowedException (guard on).
}
```

- [ ] **Step 6: Run to fail.** `./gradlew :api-fetch-http:test --tests "*HttpImageFetcherTest" --console=plain` → FAIL.

- [ ] **Step 7: Implement `HttpImageFetcher`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.fetch.http

import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchAccessDeniedException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchFailedException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchNotFoundException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchUnreachableException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageFetcher
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.TooManyRedirectsException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UrlNotAllowedException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class HttpImageFetcher(
    connectTimeout: Duration,
    private val requestTimeout: Duration,
    private val maxRedirects: Int,
    private val addressPolicy: AddressPolicy,
) : ImageFetcher {
    private val client: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override fun openStream(sourceUrl: String): InputStream {
        var url = guarded(sourceUrl)
        var redirects = 0
        while (true) {
            val response = send(url)
            val status = response.statusCode()
            when {
                status in SUCCESS_RANGE -> return response.body()
                status in REDIRECT_RANGE -> {
                    if (redirects >= maxRedirects) throw TooManyRedirectsException("too many redirects")
                    val location = response.headers().firstValue("location").orElse(null)
                        ?: throw FetchFailedException("redirect without a location header")
                    response.body().close()
                    url = guarded(url.resolve(location).toString())
                    redirects += 1
                }
                status == UNAUTHORIZED || status == FORBIDDEN ->
                    throw FetchAccessDeniedException("origin refused access ($status)")
                status == NOT_FOUND || status == GONE ->
                    throw FetchNotFoundException("no image at this url ($status)")
                status == TOO_MANY_REQUESTS || status in SERVER_ERROR_RANGE ->
                    throw FetchUnreachableException("origin error ($status)")
                else -> throw FetchFailedException("unexpected response status $status")
            }
        }
    }

    private fun send(url: URI): HttpResponse<InputStream> {
        val request = HttpRequest.newBuilder(url).timeout(requestTimeout).GET().build()
        return try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: IOException) {
            throw FetchUnreachableException("could not reach the origin", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw FetchUnreachableException("fetch interrupted", e)
        }
    }

    private fun guarded(raw: String): URI {
        val uri =
            try {
                URI(raw)
            } catch (e: URISyntaxException) {
                throw UrlNotAllowedException("malformed url", e)
            }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw UrlNotAllowedException("scheme not allowed")
        val host = uri.host ?: throw UrlNotAllowedException("missing host")
        val address =
            try {
                InetAddress.getByName(host)
            } catch (e: UnknownHostException) {
                throw FetchUnreachableException("could not resolve host", e)
            }
        if (!addressPolicy.isAllowed(address)) throw UrlNotAllowedException("address not allowed")
        return uri
    }

    private companion object {
        val SUCCESS_RANGE = 200..299
        val REDIRECT_RANGE = 300..399
        val SERVER_ERROR_RANGE = 500..599
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val GONE = 410
        const val TOO_MANY_REQUESTS = 429
    }
}
```
Note (spec §17 risk): `HttpRequest.timeout()` bounds obtaining the response headers, not the streaming body read. For v1 this is acceptable (the connect + response timeout plus the queue lease bound the worst case); if a slow-body origin becomes a problem, wrap the returned stream with a read-deadline. Leave a `// TODO(2b-followup)`-free comment noting the bound instead of a placeholder.

- [ ] **Step 8: Run both fetch tests.** `./gradlew :api-fetch-http:test --console=plain` → PASS. Confirm 100% branch on the module (`./gradlew :api-fetch-http:koverVerify --console=plain`); add any missing case (e.g. the `else -> FetchFailedException` arm needs a 418 response; the `missing host` arm needs a `http:///x` url).

- [ ] **Step 9: Commit.**
```bash
git add api-fetch-http/src
git commit -m "feat(fetch): HttpImageFetcher with the Standard SSRF address guard"
```

## Phase 5 — Use cases

### Task 9: `DownloadPinImage` (the fetch orchestration)

**Files:**
- Create: `api-usecases/.../usecases/DownloadPinImage.kt`
- Test: `api-usecases/src/test/kotlin/.../DownloadPinImageTest.kt`

**Interfaces:**
- Consumes: `PinRepositoryInterface`, `ImageRepositoryInterface`, `ImageDownloadRepositoryInterface`, `ImageStore`, `ImageProbe`, `ImageFetcher`, `TransactionRunner`, `Clock`, `TaskContext`, the `FetchException` + `ImageProbeException` + `ImageTooLargeException` families, `PermanentTaskException`.
- Produces: `@ApplicationScoped class DownloadPinImage(...)` with `fun download(pinId: UUID, context: TaskContext, maxBytes: Long, maxPixels: Long)`.

**Behaviour** (mirrors `SetPinImage`; the source URL comes from the download row, not the payload):
1. Load the download row; if missing or not PENDING → return (superseded / cancelled / hard-deleted → no-op success).
2. Load the pin; if missing → return.
3. `imageFetcher.openStream(sourceUrl).use { imageStore.stage(it, maxBytes) }`. Map failures (see below).
4. `imageProbe.probe(staged, maxPixels)`; on `ImageProbeException` → `discard` + permanent (INVALID_IMAGE / TOO_MANY_PIXELS).
5. Build the `Image`, `promote`, then the **atomic CAS swap** in `transactionRunner.inTransaction { if (deleteIfPending>0) save(image) else false }`; if not swapped, delete the promoted file (no-op success).
6. On promote/save failure → clean up both files, treat as retryable INTERNAL_ERROR.

**Failure policy**: permanent reasons → `markFailed` + throw `PermanentTaskException(reason.name)`. Retryable reasons (UNREACHABLE, INTERNAL_ERROR) → if `context.attempt >= context.maxAttempts` then `markFailed` + `PermanentTaskException`; else `recordLastError` + rethrow the cause (the processor reschedules).

- [ ] **Step 1: Write the failing tests** (MockK; relaxed store/download repos). Cover every branch. Representative subset (add the rest per the list below):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.ImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.ImageFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchAccessDeniedException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchUnreachableException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageFetcher
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageProbe
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ProbeResult
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.StagedFile
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageDownloadRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskContext
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.exceptions.PermanentTaskException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID.randomUUID

class DownloadPinImageTest {
    private val pins: PinRepositoryInterface = mockk()
    private val images: ImageRepositoryInterface = mockk(relaxed = true)
    private val downloads: ImageDownloadRepositoryInterface = mockk(relaxed = true)
    private val store: ImageStore = mockk(relaxed = true)
    private val probe: ImageProbe = mockk()
    private val fetcher: ImageFetcher = mockk()
    private val runner: TransactionRunner = mockk()
    private val clock: Clock = mockk()
    private val now = Instant.parse("2026-07-10T00:00:00Z")
    private val pinId = randomUUID()
    private val user = User(randomUUID(), "u")

    private val subject = DownloadPinImage(pins, images, downloads, store, probe, fetcher, runner, clock)

    init { every { clock.now() } returns now }

    private fun pendingRow() = ImageDownload(pinId, "https://x/i.png", DownloadStatus.PENDING, null, null, randomUUID(), now, now)
    private fun pin() = Pin(pinId, user, "https://ctx", "https://x/i.png", "d", emptyList())
    private fun ctx(attempt: Int = 1, max: Int = 3) = TaskContext(attempt, max)
    private fun staged() = StagedFile("tmp/x", 3, "hash")

    @Test
    fun `Given no PENDING download row, Then it is a no-op`() {
        every { downloads.findByPinId(pinId) } returns null
        subject.download(pinId, ctx(), 100, 100)
        verify(exactly = 0) { fetcher.openStream(any()) }
    }

    @Test
    fun `Given a 403 bounce, Then it marks FAILED ACCESS_DENIED and throws Permanent`() {
        every { downloads.findByPinId(pinId) } returns pendingRow()
        every { pins.findPinById(pinId) } returns pin()
        every { fetcher.openStream(any()) } throws FetchAccessDeniedException("403")
        assertThrows(PermanentTaskException::class.java) { subject.download(pinId, ctx(), 100, 100) }
        verify { downloads.markFailed(pinId, DownloadReason.ACCESS_DENIED, now) }
    }

    @Test
    fun `Given an unreachable origin below the attempt limit, Then it records the error and rethrows`() {
        every { downloads.findByPinId(pinId) } returns pendingRow()
        every { pins.findPinById(pinId) } returns pin()
        every { fetcher.openStream(any()) } throws FetchUnreachableException("timeout")
        assertThrows(FetchUnreachableException::class.java) { subject.download(pinId, ctx(attempt = 1, max = 3), 100, 100) }
        verify { downloads.recordLastError(pinId, "timeout", now) }
    }

    @Test
    fun `Given an unreachable origin at the attempt limit, Then it marks FAILED and throws Permanent`() {
        every { downloads.findByPinId(pinId) } returns pendingRow()
        every { pins.findPinById(pinId) } returns pin()
        every { fetcher.openStream(any()) } throws FetchUnreachableException("timeout")
        assertThrows(PermanentTaskException::class.java) { subject.download(pinId, ctx(attempt = 3, max = 3), 100, 100) }
        verify { downloads.markFailed(pinId, DownloadReason.UNREACHABLE, now) }
    }

    @Test
    fun `Given a successful fetch and a still-PENDING row, Then it promotes and swaps`() {
        every { downloads.findByPinId(pinId) } returns pendingRow()
        every { pins.findPinById(pinId) } returns pin()
        every { fetcher.openStream(any()) } returns ByteArrayInputStream(byteArrayOf(1))
        every { store.stage(any(), any()) } returns staged()
        every { probe.probe(any(), any()) } returns ProbeResult(ImageFormat.PNG, 1, 1)
        every { downloads.deleteIfPending(pinId) } returns 1
        every { runner.inTransaction<Boolean>(any()) } answers { firstArg<() -> Boolean>().invoke() }
        subject.download(pinId, ctx(), 100, 100)
        verify { store.promote(staged(), any()) }
        verify { images.save(any()) }
    }
}
```
Remaining cases to add: probe throws `UndecodableImageException` → discard + `markFailed(INVALID_IMAGE)` + Permanent; probe throws `ImageTooManyPixelsException` → `markFailed(TOO_MANY_PIXELS)`; `ImageTooLargeException` from stage → `markFailed(TOO_LARGE)` + Permanent; `UrlNotAllowedException` → `markFailed(URL_NOT_ALLOWED)`; `FetchNotFoundException` → `NOT_FOUND`; `TooManyRedirectsException`/`FetchFailedException` → `FETCH_FAILED`; swap where `deleteIfPending` returns 0 → `store.delete(storageKey)`, no `images.save`; `promote` throws → `store.discard` + `store.delete` + INTERNAL_ERROR (retryable path); pin gone (row PENDING, `findPinById` null) → no-op.

- [ ] **Step 2: Run to fail.** `./gradlew :api-usecases:test --tests "*DownloadPinImageTest" --console=plain` → FAIL.

- [ ] **Step 3: Implement `DownloadPinImage`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchAccessDeniedException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchNotFoundException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchTooLargeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchUnreachableException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageFetcher
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageProbe
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageProbeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooLargeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooManyPixelsException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ProbeResult
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.StagedFile
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.TooManyRedirectsException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UnsupportedImageFormatException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UndecodableImageException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UrlNotAllowedException
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageDownloadRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskContext
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.exceptions.PermanentTaskException
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID
import java.util.UUID.randomUUID

@ApplicationScoped
class DownloadPinImage(
    private val pinRepository: PinRepositoryInterface,
    private val imageRepository: ImageRepositoryInterface,
    private val imageDownloadRepository: ImageDownloadRepositoryInterface,
    private val imageStore: ImageStore,
    private val imageProbe: ImageProbe,
    private val imageFetcher: ImageFetcher,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    fun download(pinId: UUID, context: TaskContext, maxBytes: Long, maxPixels: Long) {
        val downloadRow = imageDownloadRepository.findByPinId(pinId)
        if (downloadRow == null || downloadRow.status != DownloadStatus.PENDING) return
        val pin = pinRepository.findPinById(pinId) ?: return

        val staged = stageFromSource(pinId, downloadRow.sourceUrl, maxBytes, context)
        val probeResult = probeStaged(pinId, staged, maxPixels)
        val image = buildImage(pin, pinId, staged, probeResult)
        promoteAndSwap(pinId, staged, image, context)
    }

    private fun stageFromSource(pinId: UUID, sourceUrl: String, maxBytes: Long, context: TaskContext): StagedFile =
        try {
            imageFetcher.openStream(sourceUrl).use { imageStore.stage(it, maxBytes) }
        } catch (e: FetchException) {
            val reason = mapFetch(e)
            if (reason == DownloadReason.UNREACHABLE) failRetryable(pinId, reason, context, e) else failPermanent(pinId, reason)
        } catch (e: ImageTooLargeException) {
            failPermanent(pinId, DownloadReason.TOO_LARGE)
        }

    private fun probeStaged(pinId: UUID, staged: StagedFile, maxPixels: Long): ProbeResult =
        try {
            imageProbe.probe(staged, maxPixels)
        } catch (e: ImageProbeException) {
            imageStore.discard(staged)
            failPermanent(pinId, mapProbe(e))
        }

    @Suppress("TooGenericExceptionCaught")
    private fun promoteAndSwap(pinId: UUID, staged: StagedFile, image: Image, context: TaskContext) {
        try {
            imageStore.promote(staged, image.storageKey)
            val swapped =
                transactionRunner.inTransaction {
                    if (imageDownloadRepository.deleteIfPending(pinId) > 0) {
                        imageRepository.save(image)
                        true
                    } else {
                        false
                    }
                }
            if (!swapped) imageStore.delete(image.storageKey)
        } catch (e: Exception) {
            imageStore.discard(staged)
            imageStore.delete(image.storageKey)
            failRetryable(pinId, DownloadReason.INTERNAL_ERROR, context, e)
        }
    }

    private fun buildImage(pin: Pin, pinId: UUID, staged: StagedFile, probe: ProbeResult): Image {
        val imageId = randomUUID()
        val storageKey = "originals/${pin.author.id}/$pinId/$imageId.${probe.format.extension}"
        return Image(
            id = imageId, pinId = pinId, mimeType = probe.format.mimeType, width = probe.width,
            height = probe.height, byteSize = staged.byteSize, contentHash = staged.contentHash,
            storageKey = storageKey, createdAt = clock.now(),
        )
    }

    private fun mapFetch(e: FetchException): DownloadReason =
        when (e) {
            is UrlNotAllowedException -> DownloadReason.URL_NOT_ALLOWED
            is FetchAccessDeniedException -> DownloadReason.ACCESS_DENIED
            is FetchNotFoundException -> DownloadReason.NOT_FOUND
            is FetchTooLargeException -> DownloadReason.TOO_LARGE
            is TooManyRedirectsException -> DownloadReason.FETCH_FAILED
            is fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchFailedException -> DownloadReason.FETCH_FAILED
            is FetchUnreachableException -> DownloadReason.UNREACHABLE
        }

    private fun mapProbe(e: ImageProbeException): DownloadReason =
        when (e) {
            is ImageTooManyPixelsException -> DownloadReason.TOO_MANY_PIXELS
            is UnsupportedImageFormatException -> DownloadReason.INVALID_IMAGE
            is UndecodableImageException -> DownloadReason.INVALID_IMAGE
        }

    private fun failPermanent(pinId: UUID, reason: DownloadReason): Nothing {
        imageDownloadRepository.markFailed(pinId, reason, clock.now())
        throw PermanentTaskException(reason.name)
    }

    private fun failRetryable(pinId: UUID, reason: DownloadReason, context: TaskContext, cause: Exception): Nothing {
        if (context.attempt >= context.maxAttempts) {
            imageDownloadRepository.markFailed(pinId, reason, clock.now())
            throw PermanentTaskException(reason.name)
        }
        imageDownloadRepository.recordLastError(pinId, cause.message ?: reason.name, clock.now())
        throw cause
    }
}
```
Note: `failPermanent`/`failRetryable` return `Nothing` (always throw), so the `try` expressions that call them typecheck as `StagedFile`/`ProbeResult`.

- [ ] **Step 4: Run the tests + coverage.** `./gradlew :api-usecases:test --tests "*DownloadPinImageTest" --console=plain` then `:api-usecases:koverVerify`. Expected: PASS + 100% branch (add cases for any uncovered `when` arm).

- [ ] **Step 5: Commit.**
```bash
git add api-usecases/src
git commit -m "feat(usecase): DownloadPinImage fetch orchestration with attempt-aware failure policy"
```

### Task 10: `RequestPinImageDownload` + `PinDownloadTask` + `IMAGE_SOURCE_URL_INVALID`

**Files:**
- Create: `api-usecases/.../usecases/tasks/PinDownloadTask.kt`
- Create: `api-usecases/.../usecases/RequestPinImageDownload.kt`
- Modify: `api-usecases/.../exceptions/ErrorCode.kt` (add `IMAGE_SOURCE_URL_INVALID`)
- Modify: `api-usecases/.../exceptions/ImageError.kt` (add `ImageSourceUrlInvalidError`)
- Modify: `api-presentation-quarkus/.../mappers/BaseErrorMapper.kt` (arm → 400; keeps the exhaustive `when` compiling)
- Test: `api-usecases/src/test/kotlin/.../RequestPinImageDownloadTest.kt`

**Interfaces:**
- Produces: `PinDownloadTask.KIND = "pin.download"`, `PinDownloadTask.MAX_ATTEMPTS = 5`; `RequestPinImageDownload.request(pinId, requester, sourceUrl): ImageDownload`; `ImageSourceUrlInvalidError` (400).

- [ ] **Step 1: Write the failing test:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.ImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageDownloadRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.Task
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePinDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImageSourceUrlInvalidError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.EnqueueTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.PinDownloadTask
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class RequestPinImageDownloadTest {
    private val pins: PinRepositoryInterface = mockk()
    private val downloads: ImageDownloadRepositoryInterface = mockk(relaxed = true)
    private val enqueue: EnqueueTask = mockk()
    private val runner: TransactionRunner = mockk()
    private val clock: Clock = mockk()
    private val now = Instant.parse("2026-07-10T00:00:00Z")
    private val owner = User(randomUUID(), "o")
    private val pinId = randomUUID()

    private val subject = RequestPinImageDownload(pins, downloads, enqueue, runner, clock)

    init {
        every { clock.now() } returns now
        every { runner.inTransaction<ImageDownload>(any()) } answers { firstArg<() -> ImageDownload>().invoke() }
    }

    private fun pin(author: User = owner) = Pin(pinId, author, "https://ctx", null, "d", emptyList())
    private fun aTask(id: java.util.UUID) = Task(id, PinDownloadTask.KIND, pinId.toString(), TaskState.PENDING, 0, now, 0, 5, null, null, false, "${PinDownloadTask.KIND}:$pinId", null)

    @Test
    fun `Given a missing pin, Then it throws ImagePinDoesNotExistError`() {
        every { pins.findPinById(pinId) } returns null
        assertThrows(ImagePinDoesNotExistError::class.java) { subject.request(pinId, owner, "https://x/i.png") }
    }

    @Test
    fun `Given a non-owner, Then it throws ImagePermissionError`() {
        every { pins.findPinById(pinId) } returns pin(author = User(randomUUID(), "other"))
        assertThrows(ImagePermissionError::class.java) { subject.request(pinId, owner, "https://x/i.png") }
    }

    @Test
    fun `Given a non-http url, Then it throws ImageSourceUrlInvalidError`() {
        every { pins.findPinById(pinId) } returns pin()
        assertThrows(ImageSourceUrlInvalidError::class.java) { subject.request(pinId, owner, "ftp://x/i.png") }
    }

    @Test
    fun `Given a valid request, Then it enqueues pin download and upserts a PENDING row atomically`() {
        val taskId = randomUUID()
        every { pins.findPinById(pinId) } returns pin()
        every { enqueue.enqueue(any(), any(), any(), any(), any(), any()) } returns aTask(taskId)
        every { downloads.upsertPending(pinId, "https://x/i.png", taskId, now) } returns
            ImageDownload(pinId, "https://x/i.png", DownloadStatus.PENDING, null, null, taskId, now, now)
        val kindSlot = slot<String>(); val payloadSlot = slot<String>(); val dedupSlot = slot<String>()
        every { enqueue.enqueue(capture(kindSlot), capture(payloadSlot), any(), any(), any(), captureNullable(dedupSlot)) } returns aTask(taskId)

        val result = subject.request(pinId, owner, "https://x/i.png")

        assertEquals(DownloadStatus.PENDING, result.status)
        assertEquals(PinDownloadTask.KIND, kindSlot.captured)
        assertEquals(pinId.toString(), payloadSlot.captured)
        assertEquals("${PinDownloadTask.KIND}:$pinId", dedupSlot.captured)
        verify { downloads.upsertPending(pinId, "https://x/i.png", taskId, now) }
    }
}
```

- [ ] **Step 2: Run to fail.** `./gradlew :api-usecases:test --tests "*RequestPinImageDownloadTest" --console=plain` → FAIL.

- [ ] **Step 3: Add the `ErrorCode` and error class.** Append `IMAGE_SOURCE_URL_INVALID,` to `ErrorCode` (after `IMAGE_INVALID`). In `ImageError.kt` add:
```kotlin
class ImageSourceUrlInvalidError : ImageError("Invalid source URL", ErrorCode.IMAGE_SOURCE_URL_INVALID)
```

- [ ] **Step 4: Map the code to 400** in `BaseErrorMapper.statusFor` (add the arm; the `when` is exhaustive so this is required to compile):
```kotlin
            ErrorCode.IMAGE_SOURCE_URL_INVALID -> Response.Status.BAD_REQUEST.statusCode
```

- [ ] **Step 5: Implement `PinDownloadTask`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

/** Identity + retry budget for the server-side image download task (mode B). */
object PinDownloadTask {
    const val KIND = "pin.download"
    const val MAX_ATTEMPTS = 5
}
```

- [ ] **Step 6: Implement `RequestPinImageDownload`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.ImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageDownloadRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePinDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImageSourceUrlInvalidError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.EnqueueTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.PinDownloadTask
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI
import java.net.URISyntaxException
import java.util.UUID

@ApplicationScoped
class RequestPinImageDownload(
    private val pinRepository: PinRepositoryInterface,
    private val imageDownloadRepository: ImageDownloadRepositoryInterface,
    private val enqueueTask: EnqueueTask,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    fun request(pinId: UUID, requester: User, sourceUrl: String): ImageDownload {
        val pin = pinRepository.findPinById(pinId) ?: throw ImagePinDoesNotExistError()
        if (pin.author.id != requester.id) throw ImagePermissionError()
        validate(sourceUrl)
        return transactionRunner.inTransaction {
            val now = clock.now()
            val task =
                enqueueTask.enqueue(
                    kind = PinDownloadTask.KIND,
                    payload = pinId.toString(),
                    maxAttempts = PinDownloadTask.MAX_ATTEMPTS,
                    dedupKey = "${PinDownloadTask.KIND}:$pinId",
                )
            imageDownloadRepository.upsertPending(pinId, sourceUrl, task.id, now)
        }
    }

    private fun validate(sourceUrl: String) {
        val scheme =
            try {
                URI(sourceUrl).scheme?.lowercase()
            } catch (e: URISyntaxException) {
                throw ImageSourceUrlInvalidError()
            }
        if (scheme != "http" && scheme != "https") throw ImageSourceUrlInvalidError()
    }
}
```

- [ ] **Step 7: Run the tests + the presentation compile** (BaseErrorMapper arm): `./gradlew :api-usecases:test --tests "*RequestPinImageDownloadTest" :api-presentation-quarkus:compileKotlin --console=plain` → PASS.

- [ ] **Step 8: Commit.**
```bash
git add api-usecases/src api-presentation-quarkus/src/main
git commit -m "feat(usecase): RequestPinImageDownload (atomic enqueue + PENDING row) and the 400 url error"
```

### Task 11: `PinImageState` (+ derive) + `ResolvePinImageState`

**Files:**
- Create: `api-usecases/.../usecases/PinImageState.kt`
- Create: `api-usecases/.../usecases/ResolvePinImageState.kt`
- Test: `api-usecases/src/test/kotlin/.../PinImageStateTest.kt`
- Test: `api-usecases/src/test/kotlin/.../ResolvePinImageStateTest.kt`

**Interfaces:**
- Produces: `PinImageStatus{NONE,PENDING,READY,FAILED}`; `PinImageReplacement(status, reasonCode)`; `PinImageState(status, image, reasonCode, replacement)` with `PinImageState.derive(image, download)`; `ResolvePinImageState.resolve(pinId, requester): PinImageState`.

- [ ] **Step 1: Write the failing `PinImageState.derive` test** (all five input combinations):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.ImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class PinImageStateTest {
    private val pinId = randomUUID()
    private fun image() = Image(randomUUID(), pinId, "image/png", 1, 1, 1, "h", "originals/x/$pinId/i.png", Instant.EPOCH)
    private fun download(status: DownloadStatus, reason: DownloadReason? = null) =
        ImageDownload(pinId, "https://x", status, reason, null, randomUUID(), Instant.EPOCH, Instant.EPOCH)

    @Test fun `Given no image and no download, Then NONE`() {
        assertEquals(PinImageStatus.NONE, PinImageState.derive(null, null).status)
    }
    @Test fun `Given no image and a PENDING download, Then PENDING`() {
        assertEquals(PinImageStatus.PENDING, PinImageState.derive(null, download(DownloadStatus.PENDING)).status)
    }
    @Test fun `Given no image and a FAILED download, Then FAILED with the reason`() {
        val s = PinImageState.derive(null, download(DownloadStatus.FAILED, DownloadReason.ACCESS_DENIED))
        assertEquals(PinImageStatus.FAILED, s.status)
        assertEquals(DownloadReason.ACCESS_DENIED, s.reasonCode)
    }
    @Test fun `Given an image and no download, Then READY with no replacement`() {
        val s = PinImageState.derive(image(), null)
        assertEquals(PinImageStatus.READY, s.status)
        assertNull(s.replacement)
    }
    @Test fun `Given an image and a PENDING download, Then READY with a PENDING replacement`() {
        val s = PinImageState.derive(image(), download(DownloadStatus.PENDING))
        assertEquals(PinImageStatus.READY, s.status)
        assertEquals(DownloadStatus.PENDING, s.replacement?.status)
    }
}
```

- [ ] **Step 2: Run to fail.** `./gradlew :api-usecases:test --tests "*PinImageStateTest" --console=plain` → FAIL.

- [ ] **Step 3: Implement `PinImageState`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.ImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus

enum class PinImageStatus { NONE, PENDING, READY, FAILED }

data class PinImageReplacement(val status: DownloadStatus, val reasonCode: DownloadReason?)

data class PinImageState(
    val status: PinImageStatus,
    val image: Image?,
    val reasonCode: DownloadReason?,
    val replacement: PinImageReplacement?,
) {
    companion object {
        fun derive(image: Image?, download: ImageDownload?): PinImageState =
            if (image != null) {
                val replacement = download?.let { PinImageReplacement(it.status, it.reasonCode) }
                PinImageState(PinImageStatus.READY, image, null, replacement)
            } else if (download == null) {
                PinImageState(PinImageStatus.NONE, null, null, null)
            } else if (download.status == DownloadStatus.PENDING) {
                PinImageState(PinImageStatus.PENDING, null, null, null)
            } else {
                PinImageState(PinImageStatus.FAILED, null, download.reasonCode, null)
            }
    }
}
```

- [ ] **Step 4: Write the failing `ResolvePinImageState` test** (owner check + delegation):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageDownloadRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePinDoesNotExistError
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class ResolvePinImageStateTest {
    private val pins: PinRepositoryInterface = mockk()
    private val images: ImageRepositoryInterface = mockk()
    private val downloads: ImageDownloadRepositoryInterface = mockk()
    private val owner = User(randomUUID(), "o")
    private val pinId = randomUUID()
    private val subject = ResolvePinImageState(pins, images, downloads)

    @Test fun `Given a missing pin, Then it throws ImagePinDoesNotExistError`() {
        every { pins.findPinById(pinId) } returns null
        assertThrows(ImagePinDoesNotExistError::class.java) { subject.resolve(pinId, owner) }
    }

    @Test fun `Given a non-owner, Then it throws ImagePermissionError`() {
        every { pins.findPinById(pinId) } returns Pin(pinId, User(randomUUID(), "x"), "c", null, "d", emptyList())
        assertThrows(ImagePermissionError::class.java) { subject.resolve(pinId, owner) }
    }

    @Test fun `Given an owner with no image and no download, Then NONE`() {
        every { pins.findPinById(pinId) } returns Pin(pinId, owner, "c", null, "d", emptyList())
        every { images.findByPinId(pinId) } returns null
        every { downloads.findByPinId(pinId) } returns null
        assertEquals(PinImageStatus.NONE, subject.resolve(pinId, owner).status)
    }
}
```

- [ ] **Step 5: Implement `ResolvePinImageState`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageDownloadRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePinDoesNotExistError
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class ResolvePinImageState(
    private val pinRepository: PinRepositoryInterface,
    private val imageRepository: ImageRepositoryInterface,
    private val imageDownloadRepository: ImageDownloadRepositoryInterface,
) {
    fun resolve(pinId: UUID, requester: User): PinImageState {
        val pin = pinRepository.findPinById(pinId) ?: throw ImagePinDoesNotExistError()
        if (pin.author.id != requester.id) throw ImagePermissionError()
        return PinImageState.derive(imageRepository.findByPinId(pinId), imageDownloadRepository.findByPinId(pinId))
    }
}
```

- [ ] **Step 6: Run the tests + coverage.** `./gradlew :api-usecases:test --tests "*PinImageStateTest" --tests "*ResolvePinImageStateTest" --console=plain` → PASS.

- [ ] **Step 7: Commit.**
```bash
git add api-usecases/src
git commit -m "feat(usecase): PinImageState derivation and ResolvePinImageState"
```

### Task 12: `ClearPinDownload` + wire into `SetPinImage` and `DeletePinImage`

**Files:**
- Create: `api-usecases/.../usecases/ClearPinDownload.kt`
- Modify: `api-usecases/.../usecases/SetPinImage.kt` (inject + call after the swap)
- Modify: `api-usecases/.../usecases/DeletePinImage.kt` (inject + call after the delete)
- Test: `api-usecases/src/test/kotlin/.../ClearPinDownloadTest.kt`
- Modify: the existing `SetPinImageTest` / `DeletePinImageTest` (add the clear dep + assert it is called)

**Interfaces:**
- Consumes: `ImageDownloadRepositoryInterface`, `CancelTask`.
- Produces: `ClearPinDownload.clear(pinId)` — cancels the pin's in-flight download task (best-effort) then deletes its download row. A direct upload / image delete supersedes any mode-B download.

- [ ] **Step 1: Write the failing `ClearPinDownload` test** (both branches: a row present → cancel + delete; no row → no cancel):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.ImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageDownloadRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.CancelTask
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class ClearPinDownloadTest {
    private val downloads: ImageDownloadRepositoryInterface = mockk(relaxed = true)
    private val cancelTask: CancelTask = mockk(relaxed = true)
    private val pinId = randomUUID()
    private val subject = ClearPinDownload(downloads, cancelTask)

    @Test fun `Given a download row, Then it cancels the task and deletes the row`() {
        val taskId = randomUUID()
        every { downloads.findByPinId(pinId) } returns
            ImageDownload(pinId, "https://x", DownloadStatus.PENDING, null, null, taskId, Instant.EPOCH, Instant.EPOCH)
        subject.clear(pinId)
        verify { cancelTask.cancel(taskId) }
        verify { downloads.deleteByPinId(pinId) }
    }

    @Test fun `Given no download row, Then it does nothing`() {
        every { downloads.findByPinId(pinId) } returns null
        subject.clear(pinId)
        verify(exactly = 0) { cancelTask.cancel(any()) }
        verify(exactly = 0) { downloads.deleteByPinId(any()) }
    }
}
```

- [ ] **Step 2: Run to fail.** `./gradlew :api-usecases:test --tests "*ClearPinDownloadTest" --console=plain` → FAIL.

- [ ] **Step 3: Implement `ClearPinDownload`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageDownloadRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.CancelTask
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Tears down a pin's mode-B download when a direct upload or an image delete supersedes it:
 * cancel the (possibly in-flight) task best-effort, then drop the download row. A still-running
 * fetch is neutralised by DownloadPinImage's CAS-on-PENDING swap, which finds no PENDING row.
 */
@ApplicationScoped
class ClearPinDownload(
    private val imageDownloadRepository: ImageDownloadRepositoryInterface,
    private val cancelTask: CancelTask,
) {
    fun clear(pinId: UUID) {
        val download = imageDownloadRepository.findByPinId(pinId) ?: return
        runCatching { cancelTask.cancel(download.taskId) }
        imageDownloadRepository.deleteByPinId(pinId)
    }
}
```

- [ ] **Step 4: Wire into `SetPinImage`.** Add `private val clearPinDownload: ClearPinDownload,` to the constructor, and call it after the best-effort supersede delete (last line before `return`):
```kotlin
        existing?.let { old -> runCatching { imageStore.delete(old.storageKey) } }
        clearPinDownload.clear(pinId)
        return SetPinImageResult(image = saved, replaced = existing != null)
```
Update `SetPinImageTest`: pass a relaxed `ClearPinDownload` mock (or a real one over relaxed repos) into the constructor; add a test asserting `clearPinDownload.clear(pinId)` is invoked on a successful set.

- [ ] **Step 5: Wire into `DeletePinImage`.** Add `private val clearPinDownload: ClearPinDownload,` to its constructor and call `clearPinDownload.clear(pinId)` after the image row + file are removed. Update `DeletePinImageTest` similarly (mock the dep; assert `clear` is called). (Read the current `DeletePinImage.kt` first to place the call after its existing delete + best-effort file removal.)

- [ ] **Step 6: Run the affected tests.** `./gradlew :api-usecases:test --tests "*ClearPinDownloadTest" --tests "*SetPinImageTest" --tests "*DeletePinImageTest" --console=plain` → PASS.

- [ ] **Step 7: Commit.**
```bash
git add api-usecases/src
git commit -m "feat(usecase): clear the mode-B download on direct upload and image delete"
```

### Task 13: Pin permanent-delete cascade → cancel download + drop row

**Files:**
- Modify: `api-usecases/.../usecases/PinRecycleBin.kt` (inject `ClearPinDownload`; call it in `permanentlyDelete` and `emptyRecycleBin`)
- Modify: `api-usecases/src/test/kotlin/.../PinRecycleBinTest.kt`

**Interfaces:**
- Consumes: `ClearPinDownload`.

- [ ] **Step 1: Update the failing test.** In `PinRecycleBinTest`, add a relaxed `ClearPinDownload` mock to the constructor and add:
```kotlin
    @Test
    fun `Given a permanently deleted pin, Then its download is cleared`() {
        // Given a soft-deleted, owned pin (reuse the existing helper that stubs findPinById + ownership)
        // When
        recycleBin.permanentlyDelete(pinId, owner)
        // Then
        verify { clearPinDownload.clear(pinId) }
    }
```
(Mirror the existing permanent-delete test's setup; add `import io.mockk.verify` if needed.)

- [ ] **Step 2: Run to fail.** `./gradlew :api-usecases:test --tests "*PinRecycleBinTest" --console=plain` → FAIL to compile (new ctor arg).

- [ ] **Step 3: Wire `ClearPinDownload` into `PinRecycleBin`.** Add `private val clearPinDownload: ClearPinDownload,` to the constructor. In `permanentlyDelete`, call `clearPinDownload.clear(pin.id)` before the image cascade:
```kotlin
    fun permanentlyDelete(pinId: UUID, user: User) {
        val pin = findPinAndValidateOwnership(pinId, user)
        if (pin.softDeletedAt == null) throw PinDeletionPinNotSoftDeletedError()
        clearPinDownload.clear(pin.id)
        val image = imageRepository.findByPinId(pin.id)
        imageRepository.deleteByPinId(pin.id)
        pinRepository.permanentlyDeletePin(pin)
        image?.let { imageStore.delete(it.storageKey) }
    }
```
In `emptyRecycleBin`, call `clearPinDownload.clear(pin.id)` inside the per-pin loop (before the image lookup):
```kotlin
        val storageKeysToDelete = pins.mapNotNull { pin ->
            clearPinDownload.clear(pin.id)
            val image = imageRepository.findByPinId(pin.id)
            imageRepository.deleteByPinId(pin.id)
            image?.storageKey
        }
```

- [ ] **Step 4: Run the test.** `./gradlew :api-usecases:test --tests "*PinRecycleBinTest" --console=plain` → PASS (add an `emptyRecycleBin` clears-each test to keep both call sites covered).

- [ ] **Step 5: Commit.**
```bash
git add api-usecases/src
git commit -m "feat(usecase): pin permanent-delete cancels and drops the mode-B download"
```

## Phase 6 — Presentation

### Task 14: `PinDownloadTaskHandler` bean

**Files:**
- Create: `api-presentation-quarkus/.../tasks/PinDownloadTaskHandler.kt`
- Test: `api-presentation-quarkus/src/test/kotlin/.../tasks/PinDownloadTaskHandlerTest.kt`

**Interfaces:**
- Consumes: `DownloadPinImage`, `ImagesConfig`, `TaskHandler`, `TaskContext`, `PinDownloadTask`.
- Produces: an `@ApplicationScoped` `TaskHandler` for `kind = "pin.download"` (auto-discovered via `Instance<TaskHandler>` in `TaskRuntimeProducers`). It parses the bare `pinId` payload and delegates, sourcing the limits from config.

- [ ] **Step 1: Write the failing test:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ImagesConfig
import fr.geoffreyCoulaud.pinryReborn.api.usecases.DownloadPinImage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.PinDownloadTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class PinDownloadTaskHandlerTest {
    private val downloadPinImage: DownloadPinImage = mockk(relaxed = true)
    private val imagesConfig: ImagesConfig = mockk()
    private val handler = PinDownloadTaskHandler(downloadPinImage, imagesConfig)

    @Test fun `Given the handler, Then its kind is pin download`() {
        assertEquals(PinDownloadTask.KIND, handler.kind)
    }

    @Test fun `Given a pinId payload, Then it delegates with the configured limits`() {
        val pinId = randomUUID()
        every { imagesConfig.maxFileBytes() } returns 100
        every { imagesConfig.maxPixels() } returns 200
        handler.handle(pinId.toString(), TaskContext(1, 5))
        verify { downloadPinImage.download(pinId, TaskContext(1, 5), 100, 200) }
    }
}
```

- [ ] **Step 2: Run to fail.** `./gradlew :api-presentation-quarkus:test --tests "*PinDownloadTaskHandlerTest" --console=plain` → FAIL.

- [ ] **Step 3: Implement `PinDownloadTaskHandler`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ImagesConfig
import fr.geoffreyCoulaud.pinryReborn.api.usecases.DownloadPinImage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.PinDownloadTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskContext
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskHandler
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class PinDownloadTaskHandler(
    private val downloadPinImage: DownloadPinImage,
    private val imagesConfig: ImagesConfig,
) : TaskHandler {
    override val kind = PinDownloadTask.KIND

    override fun handle(payload: String, context: TaskContext) {
        downloadPinImage.download(
            pinId = UUID.fromString(payload),
            context = context,
            maxBytes = imagesConfig.maxFileBytes(),
            maxPixels = imagesConfig.maxPixels(),
        )
    }
}
```

- [ ] **Step 4: Run the test.** Expected: PASS.

- [ ] **Step 5: Commit.**
```bash
git add api-presentation-quarkus/src
git commit -m "feat(presentation): pin.download TaskHandler bean"
```

### Task 15: `PinImageStateDto` + `PinImageStateMapper` + `ImageFetcher` producer

**Files:**
- Create: `api-presentation-quarkus/.../dtos/output/PinImageStateDto.kt`
- Create: `api-presentation-quarkus/.../mappers/PinImageStateMapper.kt`
- Create: `api-application/.../wiring/FetchAdapterProducers.kt`
- Test: `api-presentation-quarkus/src/test/kotlin/.../mappers/PinImageStateMapperTest.kt`

**Interfaces:**
- Produces: `PinImageStateDto` (+ nested `ReplacementDto`); `PinImageStateMapper.toDto(baseUrl, pinId)`; a composition-root `@Produces ImageFetcher`.

- [ ] **Step 1: Write the failing mapper test** (READY with url; FAILED with reasonCode + message; READY + replacement):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.PinImageStateMapper.toDto
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinImageReplacement
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinImageState
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinImageStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class PinImageStateMapperTest {
    private val pinId = randomUUID()
    private val baseUrl = "http://host"

    @Test fun `Given READY, Then the dto carries the serve url and dimensions`() {
        val img = Image(randomUUID(), pinId, "image/png", 4, 5, 6, "h", "originals/x/$pinId/i.png", Instant.EPOCH)
        val dto = PinImageState(PinImageStatus.READY, img, null, null).toDto(baseUrl, pinId)
        assertEquals("READY", dto.status)
        assertEquals("$baseUrl/api/v1/pins/$pinId/image", dto.url)
        assertEquals(4, dto.width)
    }

    @Test fun `Given FAILED, Then the dto carries the reason code and a message`() {
        val dto = PinImageState(PinImageStatus.FAILED, null, DownloadReason.ACCESS_DENIED, null).toDto(baseUrl, pinId)
        assertEquals("FAILED", dto.status)
        assertEquals("ACCESS_DENIED", dto.reasonCode)
        assertTrue(dto.message!!.isNotBlank())
    }

    @Test fun `Given READY with a FAILED replacement, Then the replacement carries its reason`() {
        val img = Image(randomUUID(), pinId, "image/png", 1, 1, 1, "h", "originals/x/$pinId/i.png", Instant.EPOCH)
        val state = PinImageState(PinImageStatus.READY, img, null, PinImageReplacement(DownloadStatus.FAILED, DownloadReason.NOT_FOUND))
        val dto = state.toDto(baseUrl, pinId)
        assertEquals("FAILED", dto.replacement?.status)
        assertEquals("NOT_FOUND", dto.replacement?.reasonCode)
    }
}
```

- [ ] **Step 2: Run to fail.** `./gradlew :api-presentation-quarkus:test --tests "*PinImageStateMapperTest" --console=plain` → FAIL.

- [ ] **Step 3: Implement `PinImageStateDto`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

data class PinImageStateDto(
    val status: String,
    val url: String? = null,
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val byteSize: Long? = null,
    val reasonCode: String? = null,
    val message: String? = null,
    val replacement: ReplacementDto? = null,
) {
    data class ReplacementDto(val status: String, val reasonCode: String? = null, val message: String? = null)
}
```

- [ ] **Step 4: Implement `PinImageStateMapper`** (the user-facing copy lives here; no long dashes):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.PinImageStateDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.PinImageStateDto.ReplacementDto
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinImageReplacement
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinImageState
import java.util.UUID

object PinImageStateMapper {
    fun PinImageState.toDto(baseUrl: String, pinId: UUID): PinImageStateDto {
        val img = image
        return PinImageStateDto(
            status = status.name,
            url = img?.let { "$baseUrl/api/v1/pins/$pinId/image" },
            mimeType = img?.mimeType,
            width = img?.width,
            height = img?.height,
            byteSize = img?.byteSize,
            reasonCode = reasonCode?.name,
            message = reasonCode?.let { messageFor(it) },
            replacement = replacement?.toDto(),
        )
    }

    private fun PinImageReplacement.toDto() =
        ReplacementDto(status = status.name, reasonCode = reasonCode?.name, message = reasonCode?.let { messageFor(it) })

    private fun messageFor(reason: DownloadReason): String =
        when (reason) {
            DownloadReason.URL_NOT_ALLOWED -> "This URL is not allowed."
            DownloadReason.UNREACHABLE -> "The server could not reach this URL."
            DownloadReason.ACCESS_DENIED -> "The site refused the server access. Upload the image directly."
            DownloadReason.NOT_FOUND -> "No image at this URL."
            DownloadReason.TOO_LARGE -> "Image too large."
            DownloadReason.INVALID_IMAGE -> "The content is not a supported image."
            DownloadReason.TOO_MANY_PIXELS -> "Dimensions too large."
            DownloadReason.INTERNAL_ERROR -> "Temporary error, try again later."
            DownloadReason.FETCH_FAILED -> "The download failed."
        }
}
```

- [ ] **Step 5: Implement the `ImageFetcher` producer** (composition root; selects the address policy by config):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.application.wiring

import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageFetcher
import fr.geoffreyCoulaud.pinryReborn.api.fetch.http.AddressPolicy
import fr.geoffreyCoulaud.pinryReborn.api.fetch.http.HttpImageFetcher
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ImageDownloadConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * CDI wiring for [ImageFetcher] in the composition root: only this module may depend on the
 * `api-fetch-http` adapter. The SSRF address policy is chosen from config: the Standard guard by
 * default, or AllowAll when `images.download.allow_private_addresses=true` (trusted networks / tests).
 */
@ApplicationScoped
class FetchAdapterProducers {
    @Produces
    @ApplicationScoped
    fun imageFetcher(config: ImageDownloadConfig): ImageFetcher {
        val policy = if (config.allowPrivateAddresses()) AddressPolicy.AllowAll else AddressPolicy.Standard
        return HttpImageFetcher(config.connectTimeout(), config.requestTimeout(), config.maxRedirects(), policy)
    }
}
```

- [ ] **Step 6: Run the mapper test + compile the app.** `./gradlew :api-presentation-quarkus:test --tests "*PinImageStateMapperTest" :api-application:compileKotlin --console=plain` → PASS.

- [ ] **Step 7: Commit.**
```bash
git add api-presentation-quarkus/src api-application/src/main
git commit -m "feat(presentation): PinImageStateDto/mapper and the ImageFetcher producer"
```

### Task 16: `ImageController` — mode-B `PUT` (JSON) + `GET .../image/status`

**Files:**
- Create: `api-presentation-quarkus/.../dtos/input/PinImageDownloadInputDto.kt`
- Modify: `api-presentation-quarkus/.../controllers/ImageController.kt`

**Interfaces:**
- Consumes: `RequestPinImageDownload`, `ResolvePinImageState`, `PinImageStateMapper`.
- Produces: `PUT /api/v1/pins/{pinId}/image` with `@Consumes(APPLICATION_JSON)` → `202`; `GET /api/v1/pins/{pinId}/image/status` → `PinImageStateDto`.

Endpoint behaviour is validated end-to-end in Task 17; this task wires the controller and confirms it compiles + the app boots. (RESTEasy Reactive routes the two `PUT` methods on the same path by `@Consumes` media type.)

- [ ] **Step 1: Create the input DTO:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input

import jakarta.validation.constraints.NotBlank

data class PinImageDownloadInputDto(
    @field:NotBlank
    val sourceUrl: String,
)
```

- [ ] **Step 2: Add the two endpoints to `ImageController`.** Inject `requestPinImageDownload: RequestPinImageDownload` and `resolvePinImageState: ResolvePinImageState` into the constructor. Add the imports (`RequestPinImageDownload`, `ResolvePinImageState`, `PinImageDownloadInputDto`, `PinImageStateDto`, `PinImageStateMapper.toDto`, `jakarta.ws.rs.core.HttpHeaders`, `jakarta.validation.Valid`) and the methods:
```kotlin
    @PUT
    @Path("/{pinId}/image")
    @Consumes(MediaType.APPLICATION_JSON)
    fun requestImageDownload(pinId: UUID, @Valid body: PinImageDownloadInputDto): RestResponse<PinImageStateDto> {
        val requester = securityIdentity.getUser()
        val download = requestPinImageDownload.request(pinId, requester, body.sourceUrl)
        val dto = PinImageState(PinImageStatus.PENDING, null, null, null).toDto(baseUrl(), pinId)
        return ResponseBuilder.create<PinImageStateDto>(RestResponse.Status.ACCEPTED, dto)
            .header(HttpHeaders.LOCATION, "${baseUrl()}/api/v1/pins/$pinId/image/status")
            .build()
    }

    @GET
    @Path("/{pinId}/image/status")
    fun getImageStatus(pinId: UUID): RestResponse<PinImageStateDto> {
        val requester = securityIdentity.getUser()
        val state = resolvePinImageState.resolve(pinId = pinId, requester = requester)
        return RestResponse.ok(state.toDto(baseUrl(), pinId))
    }
```
(Import `PinImageState`, `PinImageStatus` from `...usecases`. The `download` value is used to satisfy the request call; the `202` body reports PENDING regardless of dedup coalescing.)

- [ ] **Step 3: Compile + boot check.** `./gradlew :api-presentation-quarkus:compileKotlin --console=plain` then `:api-application:compileKotlin` → BUILD SUCCESSFUL. (Full end-to-end verification is Task 17.)

- [ ] **Step 4: Commit.**
```bash
git add api-presentation-quarkus/src
git commit -m "feat(presentation): mode-B PUT (JSON) and the image status endpoint"
```

## Phase 7 — Integration and wrap

### Task 17: End-to-end integration tests (local stub origin)

**Files:**
- Create: `api-application/src/test/kotlin/.../ModeBImageHostingIntegrationTest.kt`
- Reuse: the tiny image fixtures under `api-application/src/test/resources/fixtures/` (from 2a).

**Interfaces:**
- Consumes: the whole wired app (`@QuarkusTest`), `UserCreator`/`PinCreator` (or REST) to seed a pin, a per-class `com.sun.net.httpserver.HttpServer` origin on `127.0.0.1`.
- Prerequisite: native `libvips` (the download is probed by the real `VipsImageProbe`).

- [ ] **Step 1: Write the failing integration tests.** Use a `@TestProfile` that (a) points `images.data_dir` at a temp dir and (b) sets `images.download.allow_private_addresses=true` so the loopback origin is reachable through the real `ImageFetcher`. Start an `HttpServer` on `127.0.0.1:0` serving a PNG fixture at `/img.png`, a `403` at `/private`, a `404` at `/missing`, and a text body at `/not-image`. Cases (poll the status endpoint until settled, bounded loop):
  - **Happy path**: `PUT {sourceUrl: base/img.png}` (JSON) → `202` with `status=PENDING` and a `Location` header; poll `GET .../image/status` until `READY`; then `GET .../image` → `200` bytes + `ETag`.
  - **Bounce**: `PUT {sourceUrl: base/private}` → `202`; poll → `FAILED` with `reasonCode=ACCESS_DENIED` and a non-blank message.
  - **Not found**: `base/missing` → `FAILED` `NOT_FOUND`.
  - **Not an image**: `base/not-image` → `FAILED` `INVALID_IMAGE`.
  - **Content negotiation**: multipart `PUT` (mode A) still returns `201`/`200` on the same path; and a mode-A upload after a `FAILED` mode-B clears the status to `READY`.
  - **Replace with zero downtime**: upload mode A → `READY`; then `PUT {sourceUrl: base/img.png}` → status stays `READY` with `replacement.status=PENDING`; poll until the replacement settles and `replacement` clears.
  - **Owner-only**: another user requesting/reading status → `403`; missing pin → `404`; a `ftp://` sourceUrl → `400`.
  - **Delete cancels**: while `PENDING`, `DELETE .../image` → `204`; status → `NONE` (row dropped).
  - **Hard-delete cancels**: soft-delete then permanently-delete the pin while a download is `PENDING` → the pin (and its download) are gone.
```kotlin
// bounded status poll (e2e: the worker is real and async)
private fun pollStatus(pinId: UUID, target: String): PinImageStateResponse {
    repeat(50) {
        val body = given().auth().preemptive().basic(user, pass)
            .`when`().get("/api/v1/pins/$pinId/image/status")
            .then().statusCode(200).extract().`as`(PinImageStateResponse::class.java)
        if (body.status == target) return body
        Thread.sleep(200)
    }
    error("status did not reach $target")
}
```

- [ ] **Step 2: Run to fail.** `./gradlew :api-application:test --tests "*ModeBImageHostingIntegrationTest" --console=plain` → FAIL (endpoints/profile not fully wired yet).

- [ ] **Step 3: Fix wiring** until green: confirm `FetchAdapterProducers`, `EbeanImageDownloadRepository`, `EbeanTransactionRunner`, `PinDownloadTaskHandler`, `RequestPinImageDownload`, `ResolvePinImageState` are all ARC-discovered; the test profile sets `allow_private_addresses=true` + a temp `data_dir`; the queue poller picks up the task (default `PT1S`).

- [ ] **Step 4: Run the tests.** Expected: PASS (all cases). Confirm the queue does not livelock (the download task reaches SUCCEEDED/DEAD, not endless retry).

- [ ] **Step 5: Commit.**
```bash
git commit -am "test(images): end-to-end mode-B ingestion tests against a local origin"
```

### Task 18: Final gate, holistic review, handoff, PR

- [ ] **Step 1: Run the whole gate.** `./gradlew detekt test koverVerify --console=plain` → BUILD SUCCESSFUL (100% branch per in-gate package, including `api-fetch-http`). If a detekt `ThrowsCount`/`TooManyFunctions` threshold trips on `DownloadPinImage`/`EbeanImageDownloadRepository`, prefer a scoped `@Suppress` with a one-line rationale over splitting cohesive classes (consistent with `EbeanTaskQueue`).

- [ ] **Step 2: Holistic review** of the branch diff (`git diff main...HEAD`) for cross-cutting issues:
  - every `imageFetcher.openStream(...)` is inside a `use {}` (no leaked sockets); the staged temp is discarded on every error path; a promoted-but-unswapped file is always deleted.
  - the ambient-transaction change: `enqueue`/`save` still commit correctly in the non-ambient path (existing tests) AND join in the ambient path (new tests); no double-commit.
  - the CAS invariant holds everywhere: `markFailed`/`recordLastError`/`deleteIfPending` are all `WHERE status=PENDING`; the swap deletes only-if-pending; `ClearPinDownload` uses the unconditional `deleteByPinId` (intentional: a direct upload supersedes a FAILED row too).
  - the `pin.download` handler reads `sourceUrl` from the row (never trusts a payload URL); the payload is only ever `pinId.toString()`.
  - no layer violation: `api-usecases` imports no Jackson / no `api-fetch-http`; the `ImageFetcher` producer lives in `api-application`; no `HttpImageFetcher` construction outside the composition root.
  - `taskId` is `UUID` end to end; no leftover `Long`.
  - no top-level functions; no em-dashes in the `DownloadReason` copy.

- [ ] **Step 3: Write the handoff** in `docs/handoffs/<ISO date> - handoff - image-hosting-2b.md`: what was built (mode-B ingestion, `image_download` sidecar, status endpoint, SSRF guard, outbox), learned pitfalls (ambient-transaction wiring, the loopback-vs-SSRF test seam, the streaming-body-timeout caveat), what is NOT validated against real hardware (real remote hosts, large downloads, a broad SSRF corpus, DNS-rebinding), and the suggested next step (renditions/thumbnailing follow-up). Commit it.

- [ ] **Step 4: Open a PR** for the branch; wait for the `validate / gate` check; merge (rebase, linear history) once green. Tag `v0.3.0-image-hosting-2b` (annotated, not pushed) after merge. Clean up the branch.

---

## Self-Review

**Spec coverage** (spec §→task):
- §1 goal / §2 scope (mode B, sidecar, status, replace, cascade, SSRF, outbox, handler extension): Tasks 2-17. Out-of-scope (format conversion, renditions, ImageHash, object store, DNS-rebinding, push): intentionally not built. ✓
- §3 decisions (trigger on the image resource; `sourceMediaUrl` untouched as provenance; sidecar `ImageDownload`; delete-on-success swap; replace zero-downtime; SSRF Standard in adapter; JDK client; thin handler + pure use case; polling): Tasks 1, 3-16. ✓
- §4 modules (new `api-fetch-http`; DAG preserved): Tasks 1, 8, 15. ✓
- §5 domain model (`ImageDownload` with `taskId: UUID`, enums, `ImageFetcher` + fetch exceptions, `ImageDownloadRepositoryInterface`, `TransactionRunner`, `TaskContext`): Tasks 2, 3, 4. ✓
- §6 state machine (NONE/PENDING/READY/FAILED + replacement precedence): Task 11 (`PinImageState.derive`). ✓
- §7 REST API (content-negotiated `PUT`; `GET .../image`; `GET .../image/status`; `DELETE` extended): Tasks 12, 16, 17. ✓
- §8 download flow (fetch→stage→probe→promote→CAS swap; attempt-aware failure; idempotency): Task 9. ✓
- §9 failure taxonomy (9 reasons, retryable vs permanent): Tasks 4, 9, 15 (copy). ✓
- §10 SSRF (scheme + IP ranges per hop; redirect cap; DNS-rebinding accepted): Task 8. ✓
- §11 transactional outbox + dedup: Tasks 7, 10. ✓
- §12 lifecycle cascade (soft = leave; hard = cancel + drop; race safe via CAS): Tasks 12, 13. ✓
- §13 persistence (`image_download`, migration 1.5, `pin_id` PK, no FK, no `@Version`): Tasks 5, 6. ✓
- §14 `TaskHandler` contract extension (attempt context; zero existing handlers): Task 2. ✓
- §15 config (`images.download.*`; `< lease` invariant): Task 1. ✓
- §16 testing (TDD order, 100% branch, fake fetcher, local origin): every task test-first; Task 17 e2e. ✓
- §17 risks (streaming body timeout — noted in Task 8; multipart-vs-JSON negotiation — Task 16/17; JDK redirects — Task 8; SSRF completeness — Task 8; ambient join — Task 7; timeout < lease — Task 1). ✓
- §18 future seams: intentionally not built. ✓

**Placeholder scan:** no `TBD`/`TODO`/"add error handling" left; the streaming-body-timeout caveat (Task 8) is a documented decision, not a placeholder; the two integration-test poll helpers show real code.

**Type consistency:** `ImageDownload(pinId, sourceUrl, status, reasonCode, lastError, taskId: UUID, requestedAt, updatedAt)`, `DownloadStatus{PENDING,FAILED}`, `DownloadReason` (9), `ImageDownloadRepositoryInterface.{upsertPending,findByPinId,markFailed,recordLastError,deleteIfPending,deleteByPinId}`, `TransactionRunner.inTransaction`, `ImageFetcher.openStream`, `TaskContext(attempt,maxAttempts)`, `PinDownloadTask.{KIND,MAX_ATTEMPTS}`, `PinImageState.derive`, `DownloadPinImage.download(pinId, context, maxBytes, maxPixels)`, `RequestPinImageDownload.request(pinId, requester, sourceUrl)`, `ResolvePinImageState.resolve(pinId, requester)`, `ClearPinDownload.clear(pinId)` are used identically across the tasks that define and consume them. `taskId` is `UUID` everywhere (the spec's earlier `Long` was corrected).

**Open decision for the implementer (surfaced, not blocking):** `PinDownloadTask.MAX_ATTEMPTS` is a constant `5` (matches the queue's `defaultMaxAttempts`). If per-kind configurability is wanted, make it an `ImageDownloadConfig` accessor and thread it through `RequestPinImageDownload`; deferred here to avoid presentation config leaking into a use case.

