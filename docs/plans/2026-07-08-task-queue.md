# Persistent Task Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a generic, SQLite-backed, in-process background task queue drained by a configurable worker pool, as sub-project 1 of image hosting (spec `docs/specs/2026-07-08-task-queue.md`).

**Architecture:** Hexagonal. Pure domain (`Task`, `TaskState`, `BackoffPolicy`, `Clock` port, `TaskQueueInterface` port). Ebean adapter implements the port with typed query beans + `UpdateQuery` (no raw SQL); the fencing check is the `update()` row count. A poll-based runtime in the Quarkus adapter claims tasks in short IMMEDIATE transactions and executes them outside any transaction on a bounded worker pool. Correctness rests on SQLite's single-writer model: serialized claims are the mutual exclusion.

**Tech Stack:** Kotlin 2.4.0, Quarkus 3.37.1, Ebean 19.2.0 (query beans via kapt), SQLite (xerial 3.53.2.0, WAL), JUnit 5, MockK, `java.util.concurrent` executors (no `quarkus-scheduler`).

## Global Constraints

- **100% branch coverage per package** (Kover, `BRANCH`, `minValue=100`, `groupBy=PACKAGE`), gated in CI and pre-push. Exercise both sides of every conditional. Verify with `./gradlew koverVerify`.
- **Strict TDD**: write the failing test first, watch it fail, then minimal implementation.
- **Clean/Hexagonal**: `api-domain` pure (no I/O, no CDI, no Ebean, no clock/network/logging). `api-usecases` depends on `api-domain` only. `api-persistence-sqlite` and `api-presentation-quarkus` hold all I/O.
- **Language: English** for all identifiers and prose (comments, logs, commit messages).
- **Conventional commits**: `feat(tasks):`, `test(tasks):`, `chore(tasks):`, etc.
- **Test naming**: backticked `Given..., Then...` (no "when" in the name). Body: `// Given` / `// When` / `// Then`.
- **Ebean entities** live in `...persistence.sqlite.models` (Kover-excluded, guarded by `ModelsPackageArchTest`): every class there must be `@Entity`/`@MappedSuperclass`, declare **no functions**, and have **no custom accessors**. Put no logic there.
- **Migrations**: change an entity, then `./gradlew :api-persistence-sqlite:generateDbMigration`; hand-edit the generated `.sql` only to add SQLite partial indexes (Ebean's DDL does not emit `WHERE` predicates).
- Base package everywhere: `fr.geoffreyCoulaud.pinryReborn.api`.

---

## File Structure

New/modified files, grouped by responsibility.

**api-domain** (pure):
- Create `.../domain/time/Clock.kt` — `now(): Instant` port.
- Create `.../domain/tasks/TaskState.kt` — 5-state enum.
- Create `.../domain/tasks/Task.kt` — full domain view of a queued task.
- Create `.../domain/tasks/NewTask.kt` — enqueue input DTO.
- Create `.../domain/tasks/ClaimedTask.kt` — non-null-lease claim result.
- Create `.../domain/tasks/BackoffPolicy.kt` — `nextAttemptAt` port + `ExponentialBackoffWithJitter` impl.
- Create `.../domain/repositories/TaskQueueInterface.kt` — the queue port.

**api-usecases** (depends on api-domain only):
- Create `.../usecases/tasks/TaskHandler.kt` — `kind` + `handle(payload)`.
- Create `.../usecases/tasks/exceptions/PermanentTaskException.kt` — signals no-retry.
- Create `.../usecases/tasks/TaskHandlerRegistry.kt` — dispatch by kind.
- Create `.../usecases/tasks/TaskProcessor.kt` — execute a claimed task, settle with fencing.
- Create `.../usecases/tasks/EnqueueTask.kt`, `CancelTask.kt`, `ReapExpiredTasks.kt`.

**api-persistence-sqlite**:
- Create `.../persistence/sqlite/models/TaskModel.kt` — the `tasks` entity (no functions).
- Create `.../persistence/sqlite/mappers/TaskModelMapper.kt` — model <-> domain.
- Create `.../persistence/sqlite/repositories/EbeanTaskQueue.kt` — implements `TaskQueueInterface`.
- Modify `.../persistence/sqlite/EbeanDatabaseProducer.kt` and `src/main/resources/ebean.properties` — SQLite pragmas.
- Create migration `src/main/resources/dbmigration/1.3.sql` (+ generated model xml) — `tasks` table + partial indexes.
- Test: `.../persistence/sqlite/EbeanTaskQueueTest.kt`, `.../persistence/sqlite/EbeanTaskQueueConcurrencyTest.kt`.

**api-presentation-quarkus** (runtime adapter, `tasks` package):
- Create `.../presentation/quarkus/tasks/SystemClock.kt` — real `Clock`.
- Create `.../presentation/quarkus/tasks/TaskQueueConfig.kt` — `@ConfigMapping(prefix="tasks")`.
- Create `.../presentation/quarkus/tasks/TaskRuntimeProducers.kt` — produce `BackoffPolicy`, `TaskHandlerRegistry`.
- Create `.../presentation/quarkus/tasks/WorkerExecutor.kt` — bounded-submit abstraction + impl.
- Create `.../presentation/quarkus/tasks/TaskDispatcher.kt` — `pollOnce()` claim+submit loop.
- Create `.../presentation/quarkus/tasks/TaskWorkerLifecycle.kt` — startup/shutdown/poll cadence, drain.
- (optional metrics) Create `.../presentation/quarkus/tasks/TaskQueueMetrics.kt`.

**api-application** (composition root):
- Modify `build.gradle.kts` — add `quarkus-scheduler`? No. Add `quarkus-micrometer` only if metrics task is included.
- Modify `src/main/resources/application.properties` — `tasks.*` defaults.
- Test: `.../application/TaskQueueBootIntegrationTest.kt` — the app boots with the queue runtime.

**Build wiring**: `gradle/libs.versions.toml` (add `quarkus-core` compileOnly coordinate for lifecycle events, and `quarkus-micrometer` if metrics); `api-presentation-quarkus/build.gradle.kts` (compileOnly quarkus lifecycle + scheduler-free runtime, allOpen for new annotations if any).

---

## Phase 1 — Domain

### Task 1: Domain task value types

Pure data holders + the `Clock` port. No branches (data classes / enum) so no unit test is required to satisfy the branch gate — this matches `api-domain` which has 0 tests by design. This task produces the vocabulary every later task consumes.

**Files:**
- Create: `api-domain/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/domain/time/Clock.kt`
- Create: `api-domain/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/domain/tasks/TaskState.kt`
- Create: `api-domain/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/domain/tasks/Task.kt`
- Create: `api-domain/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/domain/tasks/NewTask.kt`
- Create: `api-domain/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/domain/tasks/ClaimedTask.kt`

**Interfaces:**
- Produces: `Clock.now(): Instant`; `TaskState { PENDING, RUNNING, SUCCEEDED, DEAD, CANCELLED }`; `Task`, `NewTask`, `ClaimedTask` (fields below).

- [ ] **Step 1: Write the types**

```kotlin
// Clock.kt
package fr.geoffreyCoulaud.pinryReborn.api.domain.time

import java.time.Instant

interface Clock {
    fun now(): Instant
}
```

```kotlin
// TaskState.kt
package fr.geoffreyCoulaud.pinryReborn.api.domain.tasks

enum class TaskState { PENDING, RUNNING, SUCCEEDED, DEAD, CANCELLED }
```

```kotlin
// Task.kt
package fr.geoffreyCoulaud.pinryReborn.api.domain.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Identifiable
import java.time.Instant
import java.util.UUID

data class Task(
    override val id: UUID,
    val kind: String,
    val payload: String,
    val state: TaskState,
    val priority: Int,
    val availableAt: Instant,
    val attempts: Int,
    val maxAttempts: Int,
    val leaseId: String?,
    val leaseExpiresAt: Instant?,
    val cancelRequested: Boolean,
    val dedupKey: String?,
    val lastError: String?,
) : Identifiable
```

```kotlin
// NewTask.kt — enqueue input
package fr.geoffreyCoulaud.pinryReborn.api.domain.tasks

import java.time.Instant

data class NewTask(
    val kind: String,
    val payload: String,
    val availableAt: Instant,
    val priority: Int = 0,
    val maxAttempts: Int,
    val dedupKey: String? = null,
)
```

```kotlin
// ClaimedTask.kt — result of a successful claim (lease guaranteed non-null)
package fr.geoffreyCoulaud.pinryReborn.api.domain.tasks

import java.util.UUID

data class ClaimedTask(
    val id: UUID,
    val kind: String,
    val payload: String,
    val attempts: Int,
    val maxAttempts: Int,
    val leaseId: String,
    val cancelRequested: Boolean,
)
```

- [ ] **Step 2: Compile**

Run: `./gradlew :api-domain:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify coverage gate is still satisfiable**

Run: `./gradlew :api-domain:koverVerify`
Expected: BUILD SUCCESSFUL (new types add 0 branches).

- [ ] **Step 4: Commit**

```bash
git add api-domain/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/domain/time api-domain/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/domain/tasks
git commit -m "feat(tasks): domain task value types and Clock port"
```

### Task 2: BackoffPolicy with full jitter

Pure, deterministic via an injected `random: () -> Double`. This is the first branchy domain logic; test exhaustively.

**Files:**
- Create: `api-domain/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/domain/tasks/BackoffPolicy.kt`
- Test: `api-domain/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/domain/tasks/ExponentialBackoffWithJitterTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `interface BackoffPolicy { fun nextAttemptAt(attempts: Int, now: Instant): Instant }`; `class ExponentialBackoffWithJitter(base: Duration, cap: Duration, random: () -> Double) : BackoffPolicy`.

Semantics: `delay = random() * min(cap, base * 2^(attempts-1))`, clamped so `attempts <= 0` behaves as `attempts == 1`. `random()` is in `[0,1)`. `nextAttemptAt = now + delay`.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ExponentialBackoffWithJitterTest {
    private val base = Duration.ofSeconds(1)
    private val cap = Duration.ofSeconds(10)
    private val now = Instant.parse("2026-07-08T00:00:00Z")

    private fun policy(random: Double) =
        ExponentialBackoffWithJitter(base = base, cap = cap, random = { random })

    @Test
    fun `Given attempt 1 and full jitter, Then delay is the base window`() {
        // Given
        val backoff = policy(random = 1.0)
        // When
        val next = backoff.nextAttemptAt(attempts = 1, now = now)
        // Then: window = min(cap, base * 2^0) = 1s ; delay = 1.0 * 1s
        assertEquals(now.plusSeconds(1), next)
    }

    @Test
    fun `Given attempt 3, Then window grows exponentially before the cap`() {
        // Given
        val backoff = policy(random = 1.0)
        // When: window = base * 2^2 = 4s (< cap)
        val next = backoff.nextAttemptAt(attempts = 3, now = now)
        // Then
        assertEquals(now.plusSeconds(4), next)
    }

    @Test
    fun `Given a large attempt, Then the window is clamped to the cap`() {
        // Given
        val backoff = policy(random = 1.0)
        // When: base * 2^9 = 512s, clamped to cap = 10s
        val next = backoff.nextAttemptAt(attempts = 10, now = now)
        // Then
        assertEquals(now.plusSeconds(10), next)
    }

    @Test
    fun `Given zero random, Then delay is zero`() {
        // Given
        val backoff = policy(random = 0.0)
        // When
        val next = backoff.nextAttemptAt(attempts = 5, now = now)
        // Then
        assertEquals(now, next)
    }

    @Test
    fun `Given attempts at or below zero, Then it behaves like attempt one`() {
        // Given
        val backoff = policy(random = 1.0)
        // When
        val next = backoff.nextAttemptAt(attempts = 0, now = now)
        // Then
        assertEquals(now.plusSeconds(1), next)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :api-domain:test --tests "*ExponentialBackoffWithJitterTest"`
Expected: FAIL (unresolved reference `ExponentialBackoffWithJitter`).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.tasks

import java.time.Duration
import java.time.Instant

interface BackoffPolicy {
    fun nextAttemptAt(attempts: Int, now: Instant): Instant
}

class ExponentialBackoffWithJitter(
    private val base: Duration,
    private val cap: Duration,
    private val random: () -> Double,
) : BackoffPolicy {
    override fun nextAttemptAt(attempts: Int, now: Instant): Instant {
        val exponent = if (attempts > 1) attempts - 1 else 0
        val window = base.multipliedBy(1L shl exponent)
        val bounded = if (window > cap) cap else window
        val delayNanos = (bounded.toNanos() * random()).toLong()
        return now.plusNanos(delayNanos)
    }
}
```

Note: `1L shl exponent` overflows past exponent 62; the cap clamp makes large exponents irrelevant, but guard the shift — cap `exponent` at 30 to avoid overflow:

```kotlin
val exponent = when {
    attempts <= 1 -> 0
    attempts - 1 > 30 -> 30
    else -> attempts - 1
}
```

Use this guarded `exponent` (three branches, all covered by the attempt=1, attempt=3, attempt=10 tests — add one more test for `attempts = 40` asserting the cap to cover the `> 30` branch).

- [ ] **Step 4: Add the overflow-guard test, then run**

```kotlin
    @Test
    fun `Given a very large attempt, Then the shift is guarded and the cap applies`() {
        // Given
        val backoff = policy(random = 1.0)
        // When
        val next = backoff.nextAttemptAt(attempts = 40, now = now)
        // Then
        assertEquals(now.plus(cap), next)
    }
```

Run: `./gradlew :api-domain:test --tests "*ExponentialBackoffWithJitterTest" && ./gradlew :api-domain:koverVerify`
Expected: PASS, coverage 100%.

- [ ] **Step 5: Commit**

```bash
git add api-domain/src/main/kotlin/.../domain/tasks/BackoffPolicy.kt api-domain/src/test/kotlin/.../domain/tasks/ExponentialBackoffWithJitterTest.kt
git commit -m "feat(tasks): exponential backoff with full jitter"
```

---

## Phase 2 — Domain port + Persistence

### Task 3: TaskQueueInterface port

The central contract, in `api-domain`. Interface only (no branches, no test). Worth its own reviewer gate.

**Files:**
- Create: `api-domain/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/domain/repositories/TaskQueueInterface.kt`

**Interfaces:**
- Consumes: `Task`, `NewTask`, `ClaimedTask`, `TaskState` (Task 1).
- Produces: the port below. All settle methods return `Boolean` (the fencing signal: `true` if the guarded update hit a row).

- [ ] **Step 1: Write the interface**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ClaimedTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.NewTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.Task
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import java.time.Duration
import java.time.Instant
import java.util.UUID

interface TaskQueueInterface {
    /** Insert a PENDING task. If [NewTask.dedupKey] matches a live (PENDING/RUNNING) task, returns that existing task without inserting. */
    fun enqueue(task: NewTask): Task

    /** Atomically claim the highest-priority, earliest-available PENDING task whose availableAt <= now, flipping it to RUNNING with a fresh lease. Returns null if none. */
    fun claimNext(now: Instant, leaseDuration: Duration): ClaimedTask?

    /** Fenced settle to SUCCEEDED. Returns false if the lease no longer matches (fenced). */
    fun markSucceeded(id: UUID, leaseId: String, now: Instant): Boolean

    /** Fenced reschedule to PENDING at [availableAt] with an incremented-attempts row already claimed. */
    fun markPendingRetry(id: UUID, leaseId: String, availableAt: Instant, now: Instant, lastError: String?): Boolean

    /** Fenced settle to DEAD. */
    fun markDead(id: UUID, leaseId: String, now: Instant, lastError: String?): Boolean

    /** Fenced: if cancelRequested is set on the leased row, settle to CANCELLED and return true; otherwise return false. */
    fun markCancelledIfRequested(id: UUID, leaseId: String, now: Instant): Boolean

    /** Cancel a PENDING task (guarded WHERE state=PENDING). Returns true if it was cancelled. */
    fun cancelPending(id: UUID): Boolean

    /** Request cancellation of a RUNNING task (sets cancelRequested WHERE state=RUNNING). Returns true if set. */
    fun requestCancel(id: UUID): Boolean

    /** Flip RUNNING rows whose lease expired (leaseExpiresAt <= now) back to PENDING. Returns the count reclaimed. */
    fun reapExpired(now: Instant): Int

    /** Count tasks currently in [state]. For metrics/inspection. */
    fun countByState(state: TaskState): Int

    /** Read a task by id (tests/inspection). */
    fun findById(id: UUID): Task?
}
```

- [ ] **Step 2: Compile & commit**

Run: `./gradlew :api-domain:compileKotlin`
Expected: BUILD SUCCESSFUL.

```bash
git add api-domain/src/main/kotlin/.../domain/repositories/TaskQueueInterface.kt
git commit -m "feat(tasks): TaskQueueInterface port"
```

### Task 4: TaskModel entity + migration

Add the `tasks` entity and its migration. The model lives in the Kover-excluded `models` package and is auto-checked by `ModelsPackageArchTest` (must be a plain `@Entity` with no functions/custom accessors).

**Files:**
- Create: `api-persistence-sqlite/src/main/kotlin/.../persistence/sqlite/models/TaskModel.kt`
- Create (generated + hand-edited): `api-persistence-sqlite/src/main/resources/dbmigration/1.3.sql` and `.../dbmigration/model/1.3.model.xml`
- Reference exemplars: `models/PinModel.kt`, `models/bases/BaseModel.kt` (`@Id id`, `@WhenCreated`, `@WhenModified`).

**Interfaces:**
- Produces: `TaskModel` entity mapped to table `tasks`; generated query bean `...models.query.QTaskModel` (kapt).

Columns mirror spec §6. Store `state` as `String` (not the domain enum, to keep the model dependency-free of domain) and timestamps as `Instant`. `id` comes from `BaseModel`.

- [ ] **Step 1: Write the entity**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.BaseModel
import io.ebean.annotation.Index
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "tasks")
class TaskModel(
    id: UUID,
    var kind: String,
    var payload: String,
    var state: String,
    var priority: Int,
    var availableAt: Instant,
    var attempts: Int,
    var maxAttempts: Int,
    var leaseId: String? = null,
    var leaseExpiresAt: Instant? = null,
    var cancelRequested: Boolean = false,
    var dedupKey: String? = null,
    var lastError: String? = null,
) : BaseModel(id = id) {
    @Version
    var version: Long = 0
}
```

Note: `@Version var version` is a property with no custom accessor and no function — it stays within the `ModelsPackageArchTest` rules (property, default getter/setter). Confirm the arch-test still passes in Step 4.

- [ ] **Step 2: Generate the migration**

Run: `./gradlew :api-persistence-sqlite:generateDbMigration`
Expected: a new `src/main/resources/dbmigration/1.3.sql` (and model xml) creating the `tasks` table.

- [ ] **Step 3: Hand-add the partial indexes**

Append to the generated `1.3.sql` (Ebean will not emit `WHERE` predicates):

```sql
-- partial claim index: only runnable rows, ordered for the claim query
create index ix_tasks_claim on tasks (priority desc, available_at asc, id asc) where state = 'PENDING';
-- partial reaper index
create index ix_tasks_lease on tasks (lease_expires_at) where state = 'RUNNING';
-- dedup uniqueness among live tasks only
create unique index ux_tasks_dedup on tasks (dedup_key) where dedup_key is not null and state in ('PENDING','RUNNING');
```

- [ ] **Step 4: Run persistence tests (migration applies, arch-test passes)**

Run: `./gradlew :api-persistence-sqlite:test`
Expected: PASS — `ModelsPackageArchTest` still green with `TaskModel` present; migrations apply on the in-memory test DB.

- [ ] **Step 5: Commit**

```bash
git add api-persistence-sqlite/src/main/kotlin/.../models/TaskModel.kt api-persistence-sqlite/src/main/resources/dbmigration/1.3.sql api-persistence-sqlite/src/main/resources/dbmigration/model/1.3.model.xml
git commit -m "feat(tasks): tasks entity and migration with partial indexes"
```

### Task 5: EbeanTaskQueue.enqueue + mapper

Start the adapter with enqueue + a model<->domain mapper. Tested against real SQLite via `RepositoryTest` (in-memory, migrations applied), exemplar `PinRepositoryTest`.

**Files:**
- Create: `api-persistence-sqlite/src/main/kotlin/.../mappers/TaskModelMapper.kt`
- Create: `api-persistence-sqlite/src/main/kotlin/.../repositories/EbeanTaskQueue.kt`
- Test: `api-persistence-sqlite/src/test/kotlin/.../EbeanTaskQueueTest.kt`
- Reference exemplars: `repositories/PinRepository.kt` (uses `@ApplicationScoped`, `Database`, `QPinModel`), `mappers/PinModelMapper.kt`.

**Interfaces:**
- Consumes: `TaskQueueInterface` (Task 3), `TaskModel`/`QTaskModel` (Task 4).
- Produces: `@ApplicationScoped class EbeanTaskQueue(database: Database) : TaskQueueInterface`. `TaskModelMapper.toDomain()` (`TaskModel.() -> Task`).

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.NewTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.EbeanTaskQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class EbeanTaskQueueTest : RepositoryTest() {
    private val queue = EbeanTaskQueue(database)
    private val now = Instant.parse("2026-07-08T00:00:00Z")

    private fun newTask(kind: String = "test.kind", dedupKey: String? = null) =
        NewTask(kind = kind, payload = "{}", availableAt = now, maxAttempts = 3, dedupKey = dedupKey)

    @Test
    fun `Given a new task, Then enqueue inserts it as PENDING`() {
        // When
        val task = queue.enqueue(newTask())
        // Then
        val stored = queue.findById(task.id)
        assertEquals(TaskState.PENDING, stored?.state)
        assertEquals("test.kind", stored?.kind)
        assertEquals(0, stored?.attempts)
    }

    @Test
    fun `Given a live task with a dedup key, Then re-enqueue coalesces to the existing task`() {
        // Given
        val first = queue.enqueue(newTask(dedupKey = "dk-1"))
        // When
        val second = queue.enqueue(newTask(dedupKey = "dk-1"))
        // Then
        assertEquals(first.id, second.id)
        assertEquals(1, queue.countByState(TaskState.PENDING))
    }

    @Test
    fun `Given no dedup key, Then two enqueues create two tasks`() {
        // Given / When
        queue.enqueue(newTask(dedupKey = null))
        queue.enqueue(newTask(dedupKey = null))
        // Then
        assertEquals(2, queue.countByState(TaskState.PENDING))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :api-persistence-sqlite:test --tests "*EbeanTaskQueueTest"`
Expected: FAIL (unresolved `EbeanTaskQueue`).

- [ ] **Step 3: Write mapper + enqueue/findById/countByState**

```kotlin
// TaskModelMapper.kt
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.Task
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.TaskModel

object TaskModelMapper {
    fun TaskModel.toDomain(): Task =
        Task(
            id = id,
            kind = kind,
            payload = payload,
            state = TaskState.valueOf(state),
            priority = priority,
            availableAt = availableAt,
            attempts = attempts,
            maxAttempts = maxAttempts,
            leaseId = leaseId,
            leaseExpiresAt = leaseExpiresAt,
            cancelRequested = cancelRequested,
            dedupKey = dedupKey,
            lastError = lastError,
        )
}
```

```kotlin
// EbeanTaskQueue.kt (enqueue + findById + countByState first; other methods added in Tasks 6-8)
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.NewTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.Task
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.TaskModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.TaskModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QTaskModel
import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID
import java.util.UUID.randomUUID

@ApplicationScoped
class EbeanTaskQueue(
    private val database: Database,
) : TaskQueueInterface {

    override fun enqueue(task: NewTask): Task {
        if (task.dedupKey != null) {
            val existing = QTaskModel(database)
                .dedupKey.equalTo(task.dedupKey)
                .state.isIn(TaskState.PENDING.name, TaskState.RUNNING.name)
                .findOne()
            if (existing != null) return existing.toDomain()
        }
        val model = TaskModel(
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

    override fun findById(id: UUID): Task? =
        QTaskModel(database).id.equalTo(id).findOne()?.toDomain()

    override fun countByState(state: TaskState): Int =
        QTaskModel(database).state.equalTo(state.name).findCount()

    // claimNext, mark*, cancel*, reapExpired added in Tasks 6-8.
    // Temporary stubs to compile; each replaced under TDD in its task:
    override fun claimNext(now: java.time.Instant, leaseDuration: java.time.Duration) = TODO()
    override fun markSucceeded(id: UUID, leaseId: String, now: java.time.Instant) = TODO()
    override fun markPendingRetry(id: UUID, leaseId: String, availableAt: java.time.Instant, now: java.time.Instant, lastError: String?) = TODO()
    override fun markDead(id: UUID, leaseId: String, now: java.time.Instant, lastError: String?) = TODO()
    override fun markCancelledIfRequested(id: UUID, leaseId: String, now: java.time.Instant) = TODO()
    override fun cancelPending(id: UUID) = TODO()
    override fun requestCancel(id: UUID) = TODO()
    override fun reapExpired(now: java.time.Instant) = TODO()
}
```

Note on `QTaskModel(database)`: mirror the existing `QPinModel()` usage in `PinRepository.kt`; the generated query bean has a no-arg constructor and a `Database`-arg constructor. Use `.equalTo`, `.isIn`, `.findOne`, `.findCount` exactly as `PinRepository` does. The `TODO()` stubs keep the class compiling; they are replaced in Tasks 6-8 and are never exercised by this task's tests (do not call them).

- [ ] **Step 4: Run to pass**

Run: `./gradlew :api-persistence-sqlite:test --tests "*EbeanTaskQueueTest"`
Expected: PASS. (Coverage is NOT yet 100% for this file because of the `TODO()` stubs; run `koverVerify` only after Task 8 completes the class. Do not commit a `koverVerify` claim here.)

- [ ] **Step 5: Commit**

```bash
git add api-persistence-sqlite/src/main/kotlin/.../mappers/TaskModelMapper.kt api-persistence-sqlite/src/main/kotlin/.../repositories/EbeanTaskQueue.kt api-persistence-sqlite/src/test/kotlin/.../EbeanTaskQueueTest.kt
git commit -m "feat(tasks): EbeanTaskQueue enqueue with dedup coalescing"
```

### Task 6: EbeanTaskQueue.claimNext

Replace the `claimNext` stub. Claim = find one runnable PENDING task ordered by the claim index, mutate to RUNNING with a fresh lease, `attempts++`, save (bumps `@Version`). Return a `ClaimedTask`.

**Files:**
- Modify: `.../repositories/EbeanTaskQueue.kt`
- Modify: `.../EbeanTaskQueueTest.kt`

**Interfaces:**
- Produces: `claimNext(now, leaseDuration): ClaimedTask?`.

- [ ] **Step 1: Add failing tests**

```kotlin
    @Test
    fun `Given a runnable task, Then claimNext returns it as RUNNING with a lease and incremented attempts`() {
        // Given
        val enqueued = queue.enqueue(newTask())
        // When
        val claimed = queue.claimNext(now, Duration.ofMinutes(1))
        // Then
        assertEquals(enqueued.id, claimed?.id)
        assertEquals(1, claimed?.attempts)
        val stored = queue.findById(enqueued.id)
        assertEquals(TaskState.RUNNING, stored?.state)
        assertEquals(claimed?.leaseId, stored?.leaseId)
    }

    @Test
    fun `Given no runnable task, Then claimNext returns null`() {
        // When / Then
        assertNull(queue.claimNext(now, Duration.ofMinutes(1)))
    }

    @Test
    fun `Given a task available in the future, Then claimNext skips it`() {
        // Given
        queue.enqueue(newTask().copy(availableAt = now.plusSeconds(60)))
        // When / Then
        assertNull(queue.claimNext(now, Duration.ofMinutes(1)))
    }

    @Test
    fun `Given two runnable tasks with different priority, Then claimNext takes the higher priority`() {
        // Given
        queue.enqueue(newTask(kind = "low"))
        queue.enqueue(NewTask(kind = "high", payload = "{}", availableAt = now, priority = 10, maxAttempts = 3))
        // When
        val claimed = queue.claimNext(now, Duration.ofMinutes(1))
        // Then
        assertEquals("high", claimed?.kind)
    }
```

(Add imports: `java.time.Duration`, `assertNull`.)

- [ ] **Step 2: Run to fail**

Run: `./gradlew :api-persistence-sqlite:test --tests "*EbeanTaskQueueTest"`
Expected: FAIL (`TODO()` / `NotImplementedError` in `claimNext`).

- [ ] **Step 3: Implement claimNext**

```kotlin
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ClaimedTask
import java.time.Duration
import java.time.Instant

override fun claimNext(now: Instant, leaseDuration: Duration): ClaimedTask? {
    val model = QTaskModel(database)
        .state.equalTo(TaskState.PENDING.name)
        .availableAt.le(now)
        .orderBy("priority desc, availableAt asc, id asc")
        .setMaxRows(1)
        .findOne()
        ?: return null
    model.state = TaskState.RUNNING.name
    model.leaseId = randomUUID().toString()
    model.leaseExpiresAt = now.plus(leaseDuration)
    model.attempts += 1
    database.save(model)
    return ClaimedTask(
        id = model.id,
        kind = model.kind,
        payload = model.payload,
        attempts = model.attempts,
        maxAttempts = model.maxAttempts,
        leaseId = requireNotNull(model.leaseId),
        cancelRequested = model.cancelRequested,
    )
}
```

Note: `.orderBy(String)`, `.setMaxRows(1)`, `.le(...)` are standard `io.ebean.Query`/query-bean methods; verify against `QPinModel` usage. `requireNotNull` avoids `!!` (detekt `UnsafeCallOnNullableType`); `leaseId` was just set non-null so the branch is always the non-throwing side — but `requireNotNull` adds a branch Kover will want covered. To avoid an uncoverable throw branch, instead capture the generated value in a local:

```kotlin
    val leaseId = randomUUID().toString()
    model.leaseId = leaseId
    ...
    return ClaimedTask(..., leaseId = leaseId, ...)
```

Use the local-variable form (no `requireNotNull`, no extra branch).

- [ ] **Step 4: Run to pass**

Run: `./gradlew :api-persistence-sqlite:test --tests "*EbeanTaskQueueTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add api-persistence-sqlite/src/main/kotlin/.../repositories/EbeanTaskQueue.kt api-persistence-sqlite/src/test/kotlin/.../EbeanTaskQueueTest.kt
git commit -m "feat(tasks): EbeanTaskQueue claimNext with lease"
```

### Task 7: EbeanTaskQueue settle methods with fencing

Replace `markSucceeded`, `markPendingRetry`, `markDead`, `markCancelledIfRequested`. Each is a typed `UpdateQuery` guarded by `id` + `leaseId`, returning `update() > 0` as the fencing signal.

**Files:**
- Modify: `.../repositories/EbeanTaskQueue.kt`, `.../EbeanTaskQueueTest.kt`

- [ ] **Step 1: Add failing tests**

```kotlin
    private fun claimFresh(): ClaimedTask {
        queue.enqueue(newTask())
        return requireNotNull(queue.claimNext(now, Duration.ofMinutes(1)))
    }

    @Test
    fun `Given a claimed task, Then markSucceeded with the right lease succeeds`() {
        // Given
        val claimed = claimFresh()
        // When
        val ok = queue.markSucceeded(claimed.id, claimed.leaseId, now)
        // Then
        assertTrue(ok)
        assertEquals(TaskState.SUCCEEDED, queue.findById(claimed.id)?.state)
    }

    @Test
    fun `Given a wrong lease, Then markSucceeded is fenced and changes nothing`() {
        // Given
        val claimed = claimFresh()
        // When
        val ok = queue.markSucceeded(claimed.id, "wrong-lease", now)
        // Then
        assertFalse(ok)
        assertEquals(TaskState.RUNNING, queue.findById(claimed.id)?.state)
    }

    @Test
    fun `Given a claimed task, Then markPendingRetry reschedules it`() {
        // Given
        val claimed = claimFresh()
        val retryAt = now.plusSeconds(30)
        // When
        val ok = queue.markPendingRetry(claimed.id, claimed.leaseId, retryAt, now, "boom")
        // Then
        assertTrue(ok)
        val stored = queue.findById(claimed.id)
        assertEquals(TaskState.PENDING, stored?.state)
        assertEquals(retryAt, stored?.availableAt)
        assertEquals("boom", stored?.lastError)
    }

    @Test
    fun `Given a claimed task, Then markDead settles it to DEAD`() {
        // Given
        val claimed = claimFresh()
        // When
        val ok = queue.markDead(claimed.id, claimed.leaseId, now, "fatal")
        // Then
        assertTrue(ok)
        assertEquals(TaskState.DEAD, queue.findById(claimed.id)?.state)
    }

    @Test
    fun `Given no cancel request, Then markCancelledIfRequested returns false`() {
        // Given
        val claimed = claimFresh()
        // When
        val cancelled = queue.markCancelledIfRequested(claimed.id, claimed.leaseId, now)
        // Then
        assertFalse(cancelled)
        assertEquals(TaskState.RUNNING, queue.findById(claimed.id)?.state)
    }

    @Test
    fun `Given a cancel request on a running task, Then markCancelledIfRequested cancels it`() {
        // Given
        val claimed = claimFresh()
        queue.requestCancel(claimed.id) // implemented in Task 8; if ordering, set cancelRequested directly via enqueue+claim path
        // When
        val cancelled = queue.markCancelledIfRequested(claimed.id, claimed.leaseId, now)
        // Then
        assertTrue(cancelled)
        assertEquals(TaskState.CANCELLED, queue.findById(claimed.id)?.state)
    }
```

If Task 8 (`requestCancel`) is not yet implemented, the last test depends on it — implement `requestCancel` here too, or reorder so Task 8's `requestCancel` precedes this test. Simplest: implement `requestCancel` and `cancelPending` in this task alongside the settles (merge Task 7 and 8). This plan merges them: **Task 7 implements all mark*/cancel* methods**; Task 8 becomes reaper + count only.

- [ ] **Step 2: Run to fail**

Run: `./gradlew :api-persistence-sqlite:test --tests "*EbeanTaskQueueTest"`
Expected: FAIL.

- [ ] **Step 3: Implement the guarded updates**

```kotlin
import io.ebean.TxScope

private fun QTaskModel.forLease(id: UUID, leaseId: String) =
    this.id.equalTo(id).leaseId.equalTo(leaseId)

override fun markSucceeded(id: UUID, leaseId: String, now: Instant): Boolean =
    QTaskModel(database).forLease(id, leaseId)
        .asUpdate()
        .set("state", TaskState.SUCCEEDED.name)
        .setRaw("version = version + 1")
        .set("updatedAt", now)   // if BaseModel exposes an updatable timestamp; otherwise omit
        .update() > 0

override fun markPendingRetry(id: UUID, leaseId: String, availableAt: Instant, now: Instant, lastError: String?): Boolean =
    QTaskModel(database).forLease(id, leaseId)
        .asUpdate()
        .set("state", TaskState.PENDING.name)
        .set("availableAt", availableAt)
        .set("lastError", lastError)
        .setNull("leaseId")
        .setNull("leaseExpiresAt")
        .setRaw("version = version + 1")
        .update() > 0

override fun markDead(id: UUID, leaseId: String, now: Instant, lastError: String?): Boolean =
    QTaskModel(database).forLease(id, leaseId)
        .asUpdate()
        .set("state", TaskState.DEAD.name)
        .set("lastError", lastError)
        .setRaw("version = version + 1")
        .update() > 0

override fun markCancelledIfRequested(id: UUID, leaseId: String, now: Instant): Boolean =
    QTaskModel(database).forLease(id, leaseId)
        .cancelRequested.equalTo(true)
        .asUpdate()
        .set("state", TaskState.CANCELLED.name)
        .setRaw("version = version + 1")
        .update() > 0

override fun cancelPending(id: UUID): Boolean =
    QTaskModel(database).id.equalTo(id)
        .state.equalTo(TaskState.PENDING.name)
        .asUpdate()
        .set("state", TaskState.CANCELLED.name)
        .setRaw("version = version + 1")
        .update() > 0

override fun requestCancel(id: UUID): Boolean =
    QTaskModel(database).id.equalTo(id)
        .state.equalTo(TaskState.RUNNING.name)
        .asUpdate()
        .set("cancelRequested", true)
        .setRaw("version = version + 1")
        .update() > 0
```

Notes:
- `.asUpdate()` converts a query-bean expression into an `UpdateQuery`; `.set(prop, value)`, `.setNull(prop)`, `.setRaw("...")` and `.update(): Int` are the verified Ebean 19.2.0 API (spec §7). `> 0` yields the `Boolean` fencing signal.
- Drop the `updatedAt` set if `BaseModel` has no such settable column (it uses `@WhenModified whenModified`, which Ebean maintains automatically on `save` but NOT on a bulk `UpdateQuery`; for bulk updates the audit timestamp will not auto-update — acceptable for v1, note in the handoff).
- `setRaw("version = version + 1")` keeps `@Version` monotonic under bulk update (Ebean does not auto-bump `@Version` on `UpdateQuery`). Confirm during implementation; if Ebean rejects manual version bump, drop it and rely on the `leaseId` guard alone for fencing (the `@Version` is a back-stop, not the primary mechanism).

- [ ] **Step 4: Run to pass**

Run: `./gradlew :api-persistence-sqlite:test --tests "*EbeanTaskQueueTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add api-persistence-sqlite/src/main/kotlin/.../repositories/EbeanTaskQueue.kt api-persistence-sqlite/src/test/kotlin/.../EbeanTaskQueueTest.kt
git commit -m "feat(tasks): fenced settle and cancellation updates"
```

### Task 8: EbeanTaskQueue.reapExpired + finish coverage

Replace the last stub (`reapExpired`) and bring the file to 100% branch.

**Files:**
- Modify: `.../repositories/EbeanTaskQueue.kt`, `.../EbeanTaskQueueTest.kt`

- [ ] **Step 1: Add failing tests**

```kotlin
    @Test
    fun `Given a running task whose lease expired, Then reapExpired returns it to PENDING`() {
        // Given
        val claimed = claimFresh() // lease 1 minute from now
        val later = now.plusSeconds(120)
        // When
        val reaped = queue.reapExpired(later)
        // Then
        assertEquals(1, reaped)
        val stored = queue.findById(claimed.id)
        assertEquals(TaskState.PENDING, stored?.state)
        assertNull(stored?.leaseId)
    }

    @Test
    fun `Given a running task with a live lease, Then reapExpired leaves it alone`() {
        // Given
        claimFresh()
        // When (before lease expiry)
        val reaped = queue.reapExpired(now.plusSeconds(1))
        // Then
        assertEquals(0, reaped)
    }
```

- [ ] **Step 2: Run to fail**, then **Step 3: implement**:

```kotlin
override fun reapExpired(now: Instant): Int =
    QTaskModel(database)
        .state.equalTo(TaskState.RUNNING.name)
        .leaseExpiresAt.le(now)
        .asUpdate()
        .set("state", TaskState.PENDING.name)
        .set("lastError", "reclaimed after lease expiry")
        .setNull("leaseId")
        .setNull("leaseExpiresAt")
        .setRaw("version = version + 1")
        .update()
```

- [ ] **Step 4: Run tests + full coverage for the module**

Run: `./gradlew :api-persistence-sqlite:test --tests "*EbeanTaskQueueTest" && ./gradlew :api-persistence-sqlite:koverVerify`
Expected: PASS, 100% branch for `repositories` and `mappers` packages (the `models` package is excluded).

- [ ] **Step 5: Commit**

```bash
git add api-persistence-sqlite/src/main/kotlin/.../repositories/EbeanTaskQueue.kt api-persistence-sqlite/src/test/kotlin/.../EbeanTaskQueueTest.kt
git commit -m "feat(tasks): reapExpired reclaims stuck leases"
```

### Task 9: SQLite pragmas (WAL, NORMAL, busy_timeout, IMMEDIATE)

Configure the production datasource so claims begin in IMMEDIATE mode with a busy timeout, WAL, and `synchronous=NORMAL`. These are connection-level settings, applied via the JDBC URL / `SQLiteConfig` in `EbeanDatabaseProducer`, and mirrored in `ebean.properties`.

**Files:**
- Modify: `.../EbeanDatabaseProducer.kt` (extend `sqliteJdbcUrl` to append pragma params) and `.../EbeanDatabaseProducerTest.kt`
- Modify: `src/main/resources/ebean.properties`
- Reference: current `sqliteJdbcUrl(dbPath)` seam and its test.

- [ ] **Step 1: Extend the failing test for the URL seam**

```kotlin
    @Test
    fun `Given a db path, Then the JDBC URL carries the queue pragmas`() {
        // When
        val url = sqliteJdbcUrl("data.db")
        // Then
        assertTrue(url.startsWith("jdbc:sqlite:data.db"))
        assertTrue(url.contains("journal_mode=WAL"))
        assertTrue(url.contains("busy_timeout=5000"))
        assertTrue(url.contains("synchronous=NORMAL"))
        assertTrue(url.contains("transaction_mode=IMMEDIATE"))
    }
```

- [ ] **Step 2: Run to fail**, **Step 3: implement** the seam:

```kotlin
internal fun sqliteJdbcUrl(dbPath: String?): String {
    val path = dbPath ?: "data.db"
    val params = listOf(
        "journal_mode=WAL",
        "synchronous=NORMAL",
        "busy_timeout=5000",
        "transaction_mode=IMMEDIATE",
    ).joinToString("&")
    return "jdbc:sqlite:$path?$params"
}
```

Notes:
- xerial sqlite-jdbc reads these as connection properties from the URL query string (`journal_mode`, `synchronous`, `busy_timeout`, `transaction_mode`). Confirm the exact parameter names against sqlite-jdbc during implementation (they map to `SQLiteConfig` pragmas); adjust if a name differs. If URL params prove unreliable, set them via `DataSourceConfig.customProperties`/init SQL instead.
- Mirror the same URL in `ebean.properties` (`datasource.db.url=jdbc:sqlite:${DB_PATH:data.db}?journal_mode=WAL&...`).

- [ ] **Step 4: Run**

Run: `./gradlew :api-persistence-sqlite:test --tests "*EbeanDatabaseProducerTest" && ./gradlew :api-persistence-sqlite:koverVerify`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add api-persistence-sqlite/src/main/kotlin/.../EbeanDatabaseProducer.kt api-persistence-sqlite/src/test/kotlin/.../EbeanDatabaseProducerTest.kt api-persistence-sqlite/src/main/resources/ebean.properties
git commit -m "chore(tasks): SQLite WAL and IMMEDIATE datasource pragmas"
```

### Task 10: Concurrency claim test (no double-claim)

Prove the claim is race-free under real multi-connection concurrency. This MUST use a **file-based** SQLite DB (a temp file), not `:memory:` (xerial gives each connection a separate in-memory DB, so `:memory:` cannot exercise cross-connection contention), configured with the Task 9 pragmas.

**Files:**
- Create: `api-persistence-sqlite/src/test/kotlin/.../EbeanTaskQueueConcurrencyTest.kt`
- Reference: `RepositoryTest` (for the default DB) is NOT reused here — this test builds its own file-backed `Database`.

**Interfaces:**
- Consumes: `EbeanTaskQueue`, the pragma URL from Task 9.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ClaimedTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.NewTask
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.EbeanTaskQueue
import io.ebean.Database
import io.ebean.DatabaseFactory
import io.ebean.datasource.DataSourceConfig
import io.ebean.config.DatabaseConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class EbeanTaskQueueConcurrencyTest {
    private val dbFile = Files.createTempFile("task-queue-conc", ".db")
    private val database: Database = buildFileDatabase(dbFile.toString())
    private val queue = EbeanTaskQueue(database)
    private val now = Instant.parse("2026-07-08T00:00:00Z")

    @AfterEach
    fun cleanup() {
        database.shutdown()
        Files.deleteIfExists(dbFile)
    }

    @Test
    fun `Given many runnable tasks and many workers, Then each task is claimed exactly once`() {
        // Given
        val total = 200
        repeat(total) {
            queue.enqueue(NewTask(kind = "k", payload = "{}", availableAt = now, maxAttempts = 1))
        }
        val claimed = ConcurrentLinkedQueue<ClaimedTask>()
        val start = CountDownLatch(1)
        val workers = (1..8).map {
            thread {
                start.await()
                while (true) {
                    val c = queue.claimNext(now, Duration.ofMinutes(1)) ?: break
                    claimed.add(c)
                }
            }
        }
        // When
        start.countDown()
        workers.forEach { it.join() }
        // Then
        val ids = claimed.map { it.id }
        assertEquals(total, ids.size)          // none lost
        assertEquals(total, ids.toSet().size)  // none double-claimed
    }
}

// Helper: build a file-backed Ebean Database with the queue pragmas + migrations run.
private fun buildFileDatabase(path: String): Database {
    val dataSource = DataSourceConfig().apply {
        url = "jdbc:sqlite:$path?journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000&transaction_mode=IMMEDIATE"
        driver = "org.sqlite.JDBC"
        username = "sa"
        password = ""
    }
    val config = DatabaseConfig().apply {
        name = "conc-test"
        isDefaultDatabase = false
        dataSourceConfig = dataSource
        isRunMigration = true
        addPackage("fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models")
    }
    return DatabaseFactory.create(config)
}
```

Note: exact `DatabaseConfig`/`DatabaseFactory` API must match Ebean 19.2.0 (mirror `EbeanDatabaseProducer`'s `Database.builder()` form instead if simpler: `Database.builder().name("conc-test").defaultDatabase(false).dataSourceBuilder(dataSource).runMigration(true).addPackage(...).build()`). Prefer the builder form used in `EbeanDatabaseProducer.kt`.

- [ ] **Step 2: Run to fail** (before Tasks 6-9 land it would fail; here it should compile and either pass or reveal a claim race).

Run: `./gradlew :api-persistence-sqlite:test --tests "*EbeanTaskQueueConcurrencyTest"`
Expected: initially may FAIL if the IMMEDIATE pragma is not effective (double-claims or `SQLITE_BUSY`). If so, that is the signal that Task 9's config is not reaching the connection — fix the pragma wiring until this passes.

- [ ] **Step 3: Make it pass** by ensuring IMMEDIATE + busy_timeout are effective (this task has no production code of its own; it validates Task 9). If claims still race, the fallback is to wrap `claimNext`'s select+update in an explicit `database.beginTransaction(TxScope.requiresNew())` and confirm the connection uses IMMEDIATE.

- [ ] **Step 4: Run repeatedly for stability**

Run: `./gradlew :api-persistence-sqlite:test --tests "*EbeanTaskQueueConcurrencyTest"` (run 3x)
Expected: PASS every time (no flakiness).

- [ ] **Step 5: Commit**

```bash
git add api-persistence-sqlite/src/test/kotlin/.../EbeanTaskQueueConcurrencyTest.kt
git commit -m "test(tasks): concurrent claim never double-claims on file-backed SQLite"
```

---

## Phase 3 — Use-cases

### Task 11: TaskHandler + registry

**Files:**
- Create: `api-usecases/src/main/kotlin/.../usecases/tasks/TaskHandler.kt`
- Create: `api-usecases/src/main/kotlin/.../usecases/tasks/exceptions/PermanentTaskException.kt`
- Create: `api-usecases/src/main/kotlin/.../usecases/tasks/TaskHandlerRegistry.kt`
- Test: `api-usecases/src/test/kotlin/.../usecases/tasks/TaskHandlerRegistryTest.kt`

**Interfaces:**
- Produces: `interface TaskHandler { val kind: String; fun handle(payload: String) }`; `class PermanentTaskException(message: String) : RuntimeException(message)`; `class TaskHandlerRegistry(handlers: List<TaskHandler>) { fun handlerFor(kind: String): TaskHandler? }`.

Contract: a handler throws `PermanentTaskException` for non-retryable failures; any other exception is treated as retryable (spec §10).

- [ ] **Step 1: Failing test**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class TaskHandlerRegistryTest {
    private fun handler(k: String) = object : TaskHandler {
        override val kind = k
        override fun handle(payload: String) = Unit
    }

    @Test
    fun `Given a registered kind, Then handlerFor returns the handler`() {
        // Given
        val h = handler("a")
        val registry = TaskHandlerRegistry(listOf(h, handler("b")))
        // When / Then
        assertSame(h, registry.handlerFor("a"))
    }

    @Test
    fun `Given an unknown kind, Then handlerFor returns null`() {
        // Given
        val registry = TaskHandlerRegistry(listOf(handler("a")))
        // When / Then
        assertNull(registry.handlerFor("z"))
    }
}
```

- [ ] **Step 2: Run to fail**, **Step 3: implement**:

```kotlin
// TaskHandler.kt
package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

interface TaskHandler {
    val kind: String
    fun handle(payload: String)
}
```
```kotlin
// exceptions/PermanentTaskException.kt
package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.exceptions

class PermanentTaskException(message: String) : RuntimeException(message)
```
```kotlin
// TaskHandlerRegistry.kt
package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

class TaskHandlerRegistry(handlers: List<TaskHandler>) {
    private val byKind: Map<String, TaskHandler> = handlers.associateBy { it.kind }
    fun handlerFor(kind: String): TaskHandler? = byKind[kind]
}
```

- [ ] **Step 4: Run + coverage**

Run: `./gradlew :api-usecases:test --tests "*TaskHandlerRegistryTest" && ./gradlew :api-usecases:koverVerify`
Expected: PASS (note: `koverVerify` for the module also needs Tasks 12-13 done; run per-test here, full koverVerify after Task 13).

- [ ] **Step 5: Commit**

```bash
git add api-usecases/src/main/kotlin/.../usecases/tasks/TaskHandler.kt api-usecases/src/main/kotlin/.../usecases/tasks/exceptions/PermanentTaskException.kt api-usecases/src/main/kotlin/.../usecases/tasks/TaskHandlerRegistry.kt api-usecases/src/test/kotlin/.../usecases/tasks/TaskHandlerRegistryTest.kt
git commit -m "feat(tasks): TaskHandler contract and registry"
```

### Task 12: TaskProcessor (execute + settle) — the core use-case

**Files:**
- Create: `api-usecases/src/main/kotlin/.../usecases/tasks/TaskProcessor.kt`
- Test: `api-usecases/src/test/kotlin/.../usecases/tasks/TaskProcessorTest.kt`

**Interfaces:**
- Consumes: `TaskQueueInterface`, `ClaimedTask`, `BackoffPolicy`, `Clock`, `TaskHandlerRegistry`, `TaskHandler`, `PermanentTaskException`.
- Produces: `@ApplicationScoped class TaskProcessor(taskQueue, registry, backoffPolicy, clock)` with `fun execute(claimed: ClaimedTask)`.

Branches to cover (all): cancelRequested-at-claim; handler present/absent; cancel-during-execute honored/not; outcome success/retryable/permanent; retryable attempts>=max / <max.

- [ ] **Step 1: Failing test (full matrix)**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.BackoffPolicy
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ClaimedTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.exceptions.PermanentTaskException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class TaskProcessorTest {
    private val queue: TaskQueueInterface = mockk(relaxed = true)
    private val backoff: BackoffPolicy = mockk()
    private val clock: Clock = mockk()
    private val now = Instant.parse("2026-07-08T00:00:00Z")

    private fun processorWith(handler: TaskHandler?) =
        TaskProcessor(
            taskQueue = queue,
            registry = TaskHandlerRegistry(listOfNotNull(handler)),
            backoffPolicy = backoff,
            clock = clock,
        )

    private fun claimed(kind: String = "k", attempts: Int = 1, maxAttempts: Int = 3, cancelRequested: Boolean = false) =
        ClaimedTask(randomUUID(), kind, "{}", attempts, maxAttempts, "lease-1", cancelRequested)

    private fun handler(kind: String, body: () -> Unit) = object : TaskHandler {
        override val kind = kind
        override fun handle(payload: String) = body()
    }

    @Test
    fun `Given a successful handler, Then the task is marked succeeded`() {
        // Given
        every { clock.now() } returns now
        val c = claimed()
        val p = processorWith(handler("k") { })
        // When
        p.execute(c)
        // Then
        verify { queue.markSucceeded(c.id, "lease-1", now) }
    }

    @Test
    fun `Given no handler for the kind, Then the task is marked dead`() {
        // Given
        every { clock.now() } returns now
        val c = claimed(kind = "unknown")
        val p = processorWith(null)
        // When
        p.execute(c)
        // Then
        verify { queue.markDead(c.id, "lease-1", now, any()) }
    }

    @Test
    fun `Given cancel requested at claim, Then the task is cancelled without running`() {
        // Given
        every { clock.now() } returns now
        var ran = false
        val c = claimed(cancelRequested = true)
        val p = processorWith(handler("k") { ran = true })
        // When
        p.execute(c)
        // Then
        verify { queue.markCancelledIfRequested(c.id, "lease-1", now) }
        assert(!ran)
    }

    @Test
    fun `Given cancel requested during execution, Then it is honored and settle is skipped`() {
        // Given
        every { clock.now() } returns now
        every { queue.markCancelledIfRequested(any(), any(), any()) } returns true
        val c = claimed()
        val p = processorWith(handler("k") { })
        // When
        p.execute(c)
        // Then
        verify(exactly = 0) { queue.markSucceeded(any(), any(), any()) }
    }

    @Test
    fun `Given a retryable failure below the attempt limit, Then it is rescheduled with backoff`() {
        // Given
        every { clock.now() } returns now
        every { queue.markCancelledIfRequested(any(), any(), any()) } returns false
        val retryAt = now.plusSeconds(4)
        every { backoff.nextAttemptAt(2, now) } returns retryAt
        val c = claimed(attempts = 2, maxAttempts = 3)
        val p = processorWith(handler("k") { throw IllegalStateException("boom") })
        // When
        p.execute(c)
        // Then
        verify { queue.markPendingRetry(c.id, "lease-1", retryAt, now, "boom") }
    }

    @Test
    fun `Given a retryable failure at the attempt limit, Then it is marked dead`() {
        // Given
        every { clock.now() } returns now
        every { queue.markCancelledIfRequested(any(), any(), any()) } returns false
        val c = claimed(attempts = 3, maxAttempts = 3)
        val p = processorWith(handler("k") { throw IllegalStateException("boom") })
        // When
        p.execute(c)
        // Then
        verify { queue.markDead(c.id, "lease-1", now, "boom") }
    }

    @Test
    fun `Given a permanent failure, Then it is marked dead without retry`() {
        // Given
        every { clock.now() } returns now
        every { queue.markCancelledIfRequested(any(), any(), any()) } returns false
        val c = claimed(attempts = 1, maxAttempts = 3)
        val p = processorWith(handler("k") { throw PermanentTaskException("nope") })
        // When
        p.execute(c)
        // Then
        verify { queue.markDead(c.id, "lease-1", now, "nope") }
    }
}
```

- [ ] **Step 2: Run to fail**, **Step 3: implement**:

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.BackoffPolicy
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ClaimedTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.exceptions.PermanentTaskException
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class TaskProcessor(
    private val taskQueue: TaskQueueInterface,
    private val registry: TaskHandlerRegistry,
    private val backoffPolicy: BackoffPolicy,
    private val clock: Clock,
) {
    private sealed interface Outcome
    private data object Success : Outcome
    private data class Retryable(val message: String) : Outcome
    private data class Permanent(val message: String) : Outcome

    fun execute(claimed: ClaimedTask) {
        if (claimed.cancelRequested) {
            taskQueue.markCancelledIfRequested(claimed.id, claimed.leaseId, clock.now())
            return
        }
        val handler = registry.handlerFor(claimed.kind)
        if (handler == null) {
            taskQueue.markDead(claimed.id, claimed.leaseId, clock.now(), "no handler for kind ${claimed.kind}")
            return
        }
        val outcome = runHandler(handler, claimed.payload)
        val now = clock.now()
        if (taskQueue.markCancelledIfRequested(claimed.id, claimed.leaseId, now)) {
            logger.info { "task ${claimed.id} cancelled during execution" }
            return
        }
        settle(claimed, outcome, now)
    }

    private fun settle(claimed: ClaimedTask, outcome: Outcome, now: java.time.Instant) {
        when (outcome) {
            is Success -> taskQueue.markSucceeded(claimed.id, claimed.leaseId, now)
            is Permanent -> taskQueue.markDead(claimed.id, claimed.leaseId, now, outcome.message)
            is Retryable ->
                if (claimed.attempts >= claimed.maxAttempts) {
                    taskQueue.markDead(claimed.id, claimed.leaseId, now, outcome.message)
                } else {
                    val retryAt = backoffPolicy.nextAttemptAt(claimed.attempts, now)
                    taskQueue.markPendingRetry(claimed.id, claimed.leaseId, retryAt, now, outcome.message)
                }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runHandler(handler: TaskHandler, payload: String): Outcome =
        try {
            handler.handle(payload)
            Success
        } catch (e: PermanentTaskException) {
            Permanent(e.message ?: "permanent failure")
        } catch (e: Exception) {
            Retryable(e.message ?: "transient failure")
        }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}
```

- [ ] **Step 4: Run + coverage per test**

Run: `./gradlew :api-usecases:test --tests "*TaskProcessorTest"`
Expected: PASS. (Ensure the retryable failure's `e.message` is non-null in tests so the `?: "transient failure"` else-branch is also covered: add one test throwing an exception with a null message, e.g. `throw RuntimeException()`, asserting `markPendingRetry(..., "transient failure")`.)

- [ ] **Step 5: Add the null-message branch test, then commit**

```kotlin
    @Test
    fun `Given a retryable failure with no message, Then a default message is used`() {
        every { clock.now() } returns now
        every { queue.markCancelledIfRequested(any(), any(), any()) } returns false
        every { backoff.nextAttemptAt(1, now) } returns now.plusSeconds(1)
        val c = claimed(attempts = 1, maxAttempts = 3)
        val p = processorWith(handler("k") { throw RuntimeException() })
        p.execute(c)
        verify { queue.markPendingRetry(c.id, "lease-1", now.plusSeconds(1), now, "transient failure") }
    }
```

```bash
git add api-usecases/src/main/kotlin/.../usecases/tasks/TaskProcessor.kt api-usecases/src/test/kotlin/.../usecases/tasks/TaskProcessorTest.kt
git commit -m "feat(tasks): TaskProcessor executes and settles claimed tasks"
```

### Task 13: EnqueueTask, CancelTask, ReapExpiredTasks

Thin orchestration over the port + clock.

**Files:**
- Create: `.../usecases/tasks/EnqueueTask.kt`, `CancelTask.kt`, `ReapExpiredTasks.kt`
- Test: `.../usecases/tasks/EnqueueTaskTest.kt`, `CancelTaskTest.kt`, `ReapExpiredTasksTest.kt`

**Interfaces:**
- Produces:
  - `@ApplicationScoped class EnqueueTask(taskQueue, clock) { fun enqueue(kind, payload, maxAttempts, delay: Duration = Duration.ZERO, priority: Int = 0, dedupKey: String? = null): Task }` (availableAt = now + delay).
  - `@ApplicationScoped class CancelTask(taskQueue) { fun cancel(id: UUID): Boolean }` = `cancelPending(id) || requestCancel(id)`.
  - `@ApplicationScoped class ReapExpiredTasks(taskQueue, clock) { fun reap(): Int }`.

- [ ] **Step 1: Failing tests**

```kotlin
// CancelTaskTest.kt (the only branchy one; EnqueueTask/ReapExpiredTasks are straight-through)
package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class CancelTaskTest {
    private val queue: TaskQueueInterface = mockk()
    private val useCase = CancelTask(queue)

    @Test
    fun `Given a pending task, Then cancel flips it and does not request cancel`() {
        // Given
        val id = randomUUID()
        every { queue.cancelPending(id) } returns true
        // When
        val result = useCase.cancel(id)
        // Then
        assertTrue(result)
        verify(exactly = 0) { queue.requestCancel(any()) }
    }

    @Test
    fun `Given a running task, Then cancel requests cancellation`() {
        // Given
        val id = randomUUID()
        every { queue.cancelPending(id) } returns false
        every { queue.requestCancel(id) } returns true
        // When
        val result = useCase.cancel(id)
        // Then
        assertTrue(result)
    }

    @Test
    fun `Given an unknown task, Then cancel returns false`() {
        // Given
        val id = randomUUID()
        every { queue.cancelPending(id) } returns false
        every { queue.requestCancel(id) } returns false
        // When / Then
        assertFalse(useCase.cancel(id))
    }
}
```

(Write analogous `EnqueueTaskTest` asserting `enqueue` builds a `NewTask` with `availableAt = now + delay` and returns the port result; and `ReapExpiredTasksTest` asserting `reap()` delegates to `taskQueue.reapExpired(clock.now())`.)

- [ ] **Step 2-3: Run to fail, implement**

```kotlin
// EnqueueTask.kt
@ApplicationScoped
class EnqueueTask(private val taskQueue: TaskQueueInterface, private val clock: Clock) {
    fun enqueue(kind: String, payload: String, maxAttempts: Int, delay: Duration = Duration.ZERO, priority: Int = 0, dedupKey: String? = null): Task =
        taskQueue.enqueue(NewTask(kind, payload, clock.now().plus(delay), priority, maxAttempts, dedupKey))
}
// CancelTask.kt
@ApplicationScoped
class CancelTask(private val taskQueue: TaskQueueInterface) {
    fun cancel(id: UUID): Boolean = taskQueue.cancelPending(id) || taskQueue.requestCancel(id)
}
// ReapExpiredTasks.kt
@ApplicationScoped
class ReapExpiredTasks(private val taskQueue: TaskQueueInterface, private val clock: Clock) {
    fun reap(): Int = taskQueue.reapExpired(clock.now())
}
```

- [ ] **Step 4: Run full module coverage**

Run: `./gradlew :api-usecases:test && ./gradlew :api-usecases:koverVerify`
Expected: PASS, 100% branch for the `usecases.tasks` package.

- [ ] **Step 5: Commit**

```bash
git add api-usecases/src/main/kotlin/.../usecases/tasks/EnqueueTask.kt api-usecases/src/main/kotlin/.../usecases/tasks/CancelTask.kt api-usecases/src/main/kotlin/.../usecases/tasks/ReapExpiredTasks.kt api-usecases/src/test/kotlin/.../usecases/tasks/
git commit -m "feat(tasks): enqueue, cancel and reap use-cases"
```

---

## Phase 4 — Runtime (api-presentation-quarkus) + wiring

### Task 14: Build wiring for lifecycle events

Add the compile-time dependency the runtime needs (`StartupEvent`/`ShutdownEvent` live in `io.quarkus:quarkus-core`).

**Files:**
- Modify: `gradle/libs.versions.toml` — add `quarkus-core = { module = "io.quarkus:quarkus-core" }`.
- Modify: `api-presentation-quarkus/build.gradle.kts` — add `compileOnly(libs.quarkus.core)`; also ensure `allOpen` already opens `jakarta.enterprise.context.ApplicationScoped` (it does).

- [ ] **Step 1: Edit the catalog**

Add under `# Quarkus` in `[libraries]`:
```toml
quarkus-core = { module = "io.quarkus:quarkus-core" }
```

- [ ] **Step 2: Edit presentation build**

Add to the `compileOnly` block:
```kotlin
    compileOnly(libs.quarkus.core)
```

- [ ] **Step 3: Compile**

Run: `./gradlew :api-presentation-quarkus:compileKotlin`
Expected: BUILD SUCCESSFUL (nothing uses it yet, but resolution is validated).

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml api-presentation-quarkus/build.gradle.kts
git commit -m "chore(tasks): add quarkus-core compileOnly for lifecycle events"
```

### Task 15: SystemClock, config, and producers

Non-branching glue. `SystemClock` and the `@ConfigMapping` interface have no branches; the producer methods are branchless. No unit tests required for the gate (0 branches), consistent with the codebase's treatment of config/producers.

**Files:**
- Create: `.../presentation/quarkus/tasks/SystemClock.kt`
- Create: `.../presentation/quarkus/tasks/TaskQueueConfig.kt`
- Create: `.../presentation/quarkus/tasks/TaskRuntimeProducers.kt`

- [ ] **Step 1: Write them**

```kotlin
// SystemClock.kt
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

@ApplicationScoped
class SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}
```

```kotlin
// TaskQueueConfig.kt
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import io.smallrye.config.ConfigMapping
import java.time.Duration

@ConfigMapping(prefix = "tasks", namingStrategy = ConfigMapping.NamingStrategy.SNAKE_CASE)
interface TaskQueueConfig {
    fun workerCount(): Int
    fun pollInterval(): Duration
    fun leaseDuration(): Duration
    fun backoffBase(): Duration
    fun backoffCap(): Duration
    fun defaultMaxAttempts(): Int
    fun shutdownDrainTimeout(): Duration
}
```

```kotlin
// TaskRuntimeProducers.kt
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.BackoffPolicy
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ExponentialBackoffWithJitter
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskHandler
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskHandlerRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.Produces
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadLocalRandom

@ApplicationScoped
class TaskRuntimeProducers {
    @Produces
    @ApplicationScoped
    fun backoffPolicy(config: TaskQueueConfig): BackoffPolicy =
        ExponentialBackoffWithJitter(config.backoffBase(), config.backoffCap()) {
            ThreadLocalRandom.current().nextDouble()
        }

    @Produces
    @ApplicationScoped
    fun taskHandlerRegistry(handlers: Instance<TaskHandler>): TaskHandlerRegistry =
        TaskHandlerRegistry(handlers.stream().toList())

    @Produces
    @ApplicationScoped
    fun workerExecutor(config: TaskQueueConfig): WorkerExecutor =
        BoundedWorkerExecutor(
            permits = Semaphore(config.workerCount()),
            pool = Executors.newFixedThreadPool(config.workerCount()),
        )

    @Produces
    @ApplicationScoped
    fun pollScheduler(): ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
}
```

(`WorkerExecutor`/`BoundedWorkerExecutor` are created in Task 16; if implementing strictly in order, add the `workerExecutor`/`pollScheduler` producers in Task 16 instead. Keep them together with their type.)

- [ ] **Step 2: Compile & commit**

Run: `./gradlew :api-presentation-quarkus:compileKotlin`

```bash
git add api-presentation-quarkus/src/main/kotlin/.../tasks/SystemClock.kt api-presentation-quarkus/src/main/kotlin/.../tasks/TaskQueueConfig.kt
git commit -m "feat(tasks): system clock and task queue config"
```

### Task 16: WorkerExecutor (bounded submit)

**Files:**
- Create: `.../presentation/quarkus/tasks/WorkerExecutor.kt`
- Test: `.../presentation/quarkus/tasks/BoundedWorkerExecutorTest.kt`

**Interfaces:**
- Produces: `interface WorkerExecutor { fun trySubmit(job: Runnable): Boolean; fun shutdownAndDrain(timeout: Duration): Boolean }`; `class BoundedWorkerExecutor(permits: Semaphore, pool: ExecutorService) : WorkerExecutor`.

Inject `Semaphore` + `ExecutorService` so tests are deterministic (supply a fake inline executor + a real semaphore).

- [ ] **Step 1: Failing test**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class BoundedWorkerExecutorTest {
    // Minimal inline ExecutorService: runs submitted work immediately, records shutdown/awaitTermination.
    private class InlineExecutor(private val awaitResult: Boolean) : AbstractExecutorService() {
        var shutdownCalled = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { shutdownCalled = true }
        override fun shutdownNow() = mutableListOf<Runnable>()
        override fun isShutdown() = shutdownCalled
        override fun isTerminated() = shutdownCalled
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = awaitResult
    }

    @Test
    fun `Given free capacity, Then trySubmit runs the job and releases the permit`() {
        // Given
        val permits = Semaphore(1)
        val exec = BoundedWorkerExecutor(permits, InlineExecutor(true))
        var ran = false
        // When
        val submitted = exec.trySubmit { ran = true }
        // Then
        assertTrue(submitted)
        assertTrue(ran)
        assertEquals(1, permits.availablePermits()) // released
    }

    @Test
    fun `Given no capacity, Then trySubmit returns false and does not run`() {
        // Given
        val permits = Semaphore(1)
        permits.acquire() // exhaust
        val exec = BoundedWorkerExecutor(permits, InlineExecutor(true))
        var ran = false
        // When
        val submitted = exec.trySubmit { ran = true }
        // Then
        assertFalse(submitted)
        assertFalse(ran)
    }

    @Test
    fun `Given the pool drains in time, Then shutdownAndDrain returns true`() {
        val exec = BoundedWorkerExecutor(Semaphore(1), InlineExecutor(awaitResult = true))
        assertTrue(exec.shutdownAndDrain(Duration.ofSeconds(1)))
    }

    @Test
    fun `Given the pool does not drain in time, Then shutdownAndDrain returns false`() {
        val exec = BoundedWorkerExecutor(Semaphore(1), InlineExecutor(awaitResult = false))
        assertFalse(exec.shutdownAndDrain(Duration.ofSeconds(1)))
    }
}
```

- [ ] **Step 2-3: Run to fail, implement**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

interface WorkerExecutor {
    fun trySubmit(job: Runnable): Boolean
    fun shutdownAndDrain(timeout: Duration): Boolean
}

class BoundedWorkerExecutor(
    private val permits: Semaphore,
    private val pool: ExecutorService,
) : WorkerExecutor {
    override fun trySubmit(job: Runnable): Boolean {
        if (!permits.tryAcquire()) return false
        pool.execute {
            try {
                job.run()
            } finally {
                permits.release()
            }
        }
        return true
    }

    override fun shutdownAndDrain(timeout: Duration): Boolean {
        pool.shutdown()
        return pool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }
}
```

- [ ] **Step 4: Run + coverage**

Run: `./gradlew :api-presentation-quarkus:test --tests "*BoundedWorkerExecutorTest"`
Expected: PASS (all branches: tryAcquire true/false, awaitTermination true/false).

- [ ] **Step 5: Commit**

```bash
git add api-presentation-quarkus/src/main/kotlin/.../tasks/WorkerExecutor.kt api-presentation-quarkus/src/test/kotlin/.../tasks/BoundedWorkerExecutorTest.kt
git commit -m "feat(tasks): bounded worker executor"
```

### Task 17: TaskDispatcher (poll one tick)

**Files:**
- Create: `.../presentation/quarkus/tasks/TaskDispatcher.kt`
- Test: `.../presentation/quarkus/tasks/TaskDispatcherTest.kt`

**Interfaces:**
- Consumes: `TaskQueueInterface`, `TaskProcessor`, `WorkerExecutor`, `Clock`, `TaskQueueConfig`.
- Produces: `@ApplicationScoped class TaskDispatcher(...) { fun pollOnce(); fun stopClaiming() }`.

Loop: while not draining, claim one; if none, stop; submit to the worker executor; if no capacity, stop (the claimed task will be reaped — a bounded, rare over-claim, acceptable for v1).

- [ ] **Step 1: Failing test**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.ClaimedTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskProcessor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID.randomUUID

class TaskDispatcherTest {
    private val queue: TaskQueueInterface = mockk()
    private val processor: TaskProcessor = mockk(relaxed = true)
    private val executor: WorkerExecutor = mockk()
    private val clock: Clock = mockk()
    private val config: TaskQueueConfig = mockk()
    private val now = Instant.parse("2026-07-08T00:00:00Z")

    private fun dispatcher() = TaskDispatcher(queue, processor, executor, clock, config)
    private fun claim() = ClaimedTask(randomUUID(), "k", "{}", 1, 3, "l", false)

    init {
        every { clock.now() } returns now
        every { config.leaseDuration() } returns Duration.ofMinutes(1)
    }

    @Test
    fun `Given two tasks and capacity, Then both are claimed and submitted`() {
        // Given
        val a = claim(); val b = claim()
        every { queue.claimNext(now, any()) } returnsMany listOf(a, b, null)
        every { executor.trySubmit(any()) } answers { firstArg<Runnable>().run(); true }
        // When
        dispatcher().pollOnce()
        // Then
        verify { processor.execute(a) }
        verify { processor.execute(b) }
    }

    @Test
    fun `Given no capacity, Then it stops after the first claim`() {
        // Given
        every { queue.claimNext(now, any()) } returns claim()
        every { executor.trySubmit(any()) } returns false
        // When
        dispatcher().pollOnce()
        // Then
        verify(exactly = 1) { queue.claimNext(now, any()) }
    }

    @Test
    fun `Given draining, Then no task is claimed`() {
        // Given
        val d = dispatcher()
        d.stopClaiming()
        // When
        d.pollOnce()
        // Then
        verify(exactly = 0) { queue.claimNext(any(), any()) }
    }
}
```

- [ ] **Step 2-3: Run to fail, implement**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskProcessor
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class TaskDispatcher(
    private val taskQueue: TaskQueueInterface,
    private val taskProcessor: TaskProcessor,
    private val workerExecutor: WorkerExecutor,
    private val clock: Clock,
    private val config: TaskQueueConfig,
) {
    @Volatile
    private var draining = false

    fun stopClaiming() {
        draining = true
    }

    fun pollOnce() {
        while (!draining) {
            val claimed = taskQueue.claimNext(clock.now(), config.leaseDuration()) ?: break
            val submitted = workerExecutor.trySubmit { taskProcessor.execute(claimed) }
            if (!submitted) break
        }
    }
}
```

- [ ] **Step 4: Run + coverage**

Run: `./gradlew :api-presentation-quarkus:test --tests "*TaskDispatcherTest"`
Expected: PASS (branches: `!draining` true/false, claimed null/non-null, submitted true/false).

- [ ] **Step 5: Commit**

```bash
git add api-presentation-quarkus/src/main/kotlin/.../tasks/TaskDispatcher.kt api-presentation-quarkus/src/test/kotlin/.../tasks/TaskDispatcherTest.kt
git commit -m "feat(tasks): task dispatcher poll tick"
```

### Task 18: TaskWorkerLifecycle (startup, poll cadence, graceful drain)

**Files:**
- Create: `.../presentation/quarkus/tasks/TaskWorkerLifecycle.kt`
- Test: `.../presentation/quarkus/tasks/TaskWorkerLifecycleTest.kt`

**Interfaces:**
- Consumes: `TaskDispatcher`, `ReapExpiredTasks`, `WorkerExecutor`, `ScheduledExecutorService`, `TaskQueueConfig`.
- Produces: `@ApplicationScoped class TaskWorkerLifecycle(...)` with `onStart(@Observes StartupEvent)`, `onStop(@Observes ShutdownEvent)`, and testable `start()`, `safePoll()`, `stop()`.

- [ ] **Step 1: Failing test**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.ReapExpiredTasks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class TaskWorkerLifecycleTest {
    private val dispatcher: TaskDispatcher = mockk(relaxed = true)
    private val reap: ReapExpiredTasks = mockk(relaxed = true)
    private val executor: WorkerExecutor = mockk()
    private val scheduler: ScheduledExecutorService = mockk(relaxed = true)
    private val config: TaskQueueConfig = mockk()

    private fun lifecycle() = TaskWorkerLifecycle(dispatcher, reap, executor, scheduler, config)

    init {
        every { config.pollInterval() } returns Duration.ofSeconds(1)
        every { config.shutdownDrainTimeout() } returns Duration.ofSeconds(5)
    }

    @Test
    fun `Given startup, Then it reaps orphans and schedules polling`() {
        // When
        lifecycle().start()
        // Then
        verify { reap.reap() }
        verify { scheduler.scheduleWithFixedDelay(any(), 0L, 1000L, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `Given a poll failure, Then safePoll swallows it`() {
        // Given
        every { dispatcher.pollOnce() } throws RuntimeException("boom")
        // When / Then (no exception escapes)
        lifecycle().safePoll()
        verify { dispatcher.pollOnce() }
    }

    @Test
    fun `Given a clean poll, Then safePoll delegates once`() {
        // Given
        every { dispatcher.pollOnce() } returns Unit
        // When
        lifecycle().safePoll()
        // Then
        verify(exactly = 1) { dispatcher.pollOnce() }
    }

    @Test
    fun `Given shutdown that drains in time, Then it stops claiming and does not warn`() {
        // Given
        every { executor.shutdownAndDrain(any()) } returns true
        // When
        lifecycle().stop()
        // Then
        verify { dispatcher.stopClaiming() }
        verify { scheduler.shutdown() }
    }

    @Test
    fun `Given shutdown that does not drain, Then the warn branch is taken`() {
        // Given
        every { executor.shutdownAndDrain(any()) } returns false
        // When / Then (covers the if-!drained branch)
        lifecycle().stop()
        verify { executor.shutdownAndDrain(Duration.ofSeconds(5)) }
    }
}
```

- [ ] **Step 2-3: Run to fail, implement**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.ReapExpiredTasks
import io.github.oshai.kotlinlogging.KotlinLogging
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@ApplicationScoped
class TaskWorkerLifecycle(
    private val dispatcher: TaskDispatcher,
    private val reapExpiredTasks: ReapExpiredTasks,
    private val workerExecutor: WorkerExecutor,
    private val pollScheduler: ScheduledExecutorService,
    private val config: TaskQueueConfig,
) {
    fun onStart(@Observes event: StartupEvent) = start()

    fun onStop(@Observes event: ShutdownEvent) = stop()

    fun start() {
        reapExpiredTasks.reap()
        pollScheduler.scheduleWithFixedDelay(
            { safePoll() },
            0L,
            config.pollInterval().toMillis(),
            TimeUnit.MILLISECONDS,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    fun safePoll() {
        try {
            dispatcher.pollOnce()
        } catch (e: Exception) {
            logger.error(e) { "task poll failed" }
        }
    }

    fun stop() {
        dispatcher.stopClaiming()
        pollScheduler.shutdown()
        if (!workerExecutor.shutdownAndDrain(config.shutdownDrainTimeout())) {
            logger.warn { "task worker pool did not drain within the shutdown timeout" }
        }
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}
```

Note: `onStart`/`onStop` are branchless delegations; calling `start()`/`safePoll()`/`stop()` directly in tests covers all branches. If Kover flags the `@Observes` methods, add trivial tests calling `lifecycle().onStart(mockk())` / `onStop(mockk())`.

- [ ] **Step 4: Run + full module coverage**

Run: `./gradlew :api-presentation-quarkus:test && ./gradlew :api-presentation-quarkus:koverVerify`
Expected: PASS, 100% branch for the `tasks` package.

- [ ] **Step 5: Commit**

```bash
git add api-presentation-quarkus/src/main/kotlin/.../tasks/TaskWorkerLifecycle.kt api-presentation-quarkus/src/main/kotlin/.../tasks/TaskRuntimeProducers.kt api-presentation-quarkus/src/test/kotlin/.../tasks/TaskWorkerLifecycleTest.kt
git commit -m "feat(tasks): worker lifecycle with graceful drain"
```

### Task 19: Application wiring + boot integration test

Wire config defaults and prove the whole runtime boots and processes a task end-to-end (a task with an unknown kind is claimed and settled to DEAD by the no-handler path, which exercises claim -> execute -> settle through real Quarkus + real SQLite).

**Files:**
- Modify: `api-application/src/main/resources/application.properties`
- Modify: `api-application/src/test/resources/application.properties` (fast poll for the test)
- Create: `api-application/src/test/kotlin/.../application/TaskQueueBootIntegrationTest.kt`
- Reference exemplar: `application/IntegrationTest.kt` (`@QuarkusTest` base).

- [ ] **Step 1: Add config defaults**

`api-application/src/main/resources/application.properties`:
```properties
# Task queue
tasks.worker-count=4
tasks.poll-interval=PT1S
tasks.lease-duration=PT1M
tasks.backoff-base=PT1S
tasks.backoff-cap=PT5M
tasks.default-max-attempts=5
tasks.shutdown-drain-timeout=PT20S
```
`api-application/src/test/resources/application.properties` (append): `tasks.poll-interval=PT0.05S` and the same keys (faster poll for the test).

- [ ] **Step 2: Write the boot integration test**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.EnqueueTask
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

@QuarkusTest
class TaskQueueBootIntegrationTest {
    @Inject lateinit var enqueueTask: EnqueueTask
    @Inject lateinit var taskQueue: TaskQueueInterface

    @Test
    fun `Given an enqueued task with no handler, Then the runtime settles it to DEAD`() {
        // Given
        val task = enqueueTask.enqueue(kind = "no.handler", payload = "{}", maxAttempts = 1)

        // When: the background poller claims and processes it (no handler -> DEAD)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var state = taskQueue.findById(task.id)?.state
        while (state != TaskState.DEAD && System.nanoTime() < deadline) {
            Thread.sleep(50)
            state = taskQueue.findById(task.id)?.state
        }

        // Then
        assertEquals(TaskState.DEAD, state)
    }
}
```

Note: this test needs a real on-disk SQLite (the `TaskModel` + migrations). Confirm the `@QuarkusTest` profile uses a file DB (via `DB_PATH`/`application.properties`) so the background threads and the test thread share one database; if the integration profile uses `:memory:`, point it at a temp file for this test (Quarkus test resource or a `%test.` DB path). This mirrors the concurrency-test gotcha (Task 10).

- [ ] **Step 3: Run**

Run: `./gradlew :api-application:test --tests "*TaskQueueBootIntegrationTest"`
Expected: PASS (task reaches DEAD within the timeout).

- [ ] **Step 4: Full gate**

Run: `./gradlew check koverVerify`
Expected: BUILD SUCCESSFUL (detekt + all tests + 100% branch on every in-gate module). Regenerate OpenAPI if the build changed it (no REST endpoints added, so no change expected; if `docs/openapi.json` changes, commit it).

- [ ] **Step 5: Commit**

```bash
git add api-application/src/main/resources/application.properties api-application/src/test/resources/application.properties api-application/src/test/kotlin/.../application/TaskQueueBootIntegrationTest.kt
git commit -m "feat(tasks): wire task queue runtime and boot integration test"
```

### Task 20 (optional, deferrable): Micrometer metrics

Spec §14 lists minimal Micrometer gauges. This is deferrable (structured logs already cover transitions). If included: add `quarkus-micrometer` (+ a registry) to `api-application`, and a `TaskQueueMetrics` bean registering gauges over `taskQueue.countByState(...)` for `PENDING`/`RUNNING`/`DEAD`, plus an `@Observes StartupEvent` to register them. Test the gauge-value suppliers (each is a lambda over `countByState`) with a mocked `TaskQueueInterface`.

If deferring, `log()` the decision in the handoff (spec §14 partially satisfied by logging only).

---

## Self-Review

- **Spec coverage:** §3 claim insight → Task 6/10; §5 states → TaskState (Task 1) + settle methods (Task 7); §6 schema/indexes → Task 4; §7 claim/execute/settle + Ebean typed API → Tasks 6-7; §8 lease+reaper+fencing → Tasks 6-8, 12; §9 cancellation → Tasks 7, 12, 13; §10 backoff/retries/dead → Tasks 2, 12; §11 transactional enqueue + dedup → Task 5 (enqueue joins the caller's Ebean transaction because `EbeanTaskQueue.enqueue` runs on the ambient transaction; the same-transaction guarantee is exercised by sub-project 2's caller and noted below); §12 runtime → Tasks 15-19; §13 pragmas → Task 9; §14 ordering/observability/config → Tasks 6, 15, 18, (20); §17 testing → every task; §18 verification → Task 19.
- **Transactional enqueue gap:** the port's `enqueue` runs within whatever Ebean transaction is active when called; the "same transaction as the domain change" guarantee is realized by the sub-project-2 caller (create Pin + enqueue in one `@Transactional`). This plan does not add a standalone transactional-enqueue test because there is no domain-write caller yet; that test belongs to sub-project 2. Flagged, not silently dropped.
- **Placeholder scan:** the `TODO()` stubs in Task 5 are intentional, replaced under TDD in Tasks 6-8, and never executed by Task 5's tests; every other step ships real code.
- **Type consistency:** `ClaimedTask`, `NewTask`, `Task`, `TaskState`, and the `TaskQueueInterface` method names (`claimNext`, `markSucceeded`, `markPendingRetry`, `markDead`, `markCancelledIfRequested`, `cancelPending`, `requestCancel`, `reapExpired`, `countByState`, `findById`) are used identically across Tasks 3, 5-8, 12, 13, 17. `WorkerExecutor.trySubmit/shutdownAndDrain`, `TaskDispatcher.pollOnce/stopClaiming`, `TaskWorkerLifecycle.start/safePoll/stop` are consistent across Tasks 15-19.
- **Known risks carried from the spec (§20), to resolve during implementation:** exact sqlite-jdbc pragma parameter names (Task 9); the `setRaw("version = version + 1")` manual `@Version` bump under `UpdateQuery` (Task 7 — fall back to lease-guard-only fencing if Ebean rejects it); the file-vs-`:memory:` DB requirement for concurrency and the boot test (Tasks 10, 19); Ebean query-bean method surface for `orderBy`/`setMaxRows`/`asUpdate` (mirror `PinRepository`).

