# Structural soft-delete read isolation, implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking. Per the project's Plan phase, this plan is reviewed by a fresh subagent before any dispatch,
> and each task is reviewed by a fresh subagent on completion (the implementer never reviews its own task).

**Goal:** Confine the `io.ebean.Database` capability behind two persistence-internal ports so an unfiltered
read of a recyclable model is structurally unexpressible outside `queries`, in one pass.

**Architecture:** `Persistor` (writes) and `TransactionControl` (transaction lifecycle) wrap the single
`Database`, which is then referenced only in the producer and the two port implementations. Konsist
assertions hold that closed. `ModelRepository` stops extending `BeanRepository`. No behaviour changes.

**Tech Stack:** Kotlin, Quarkus (CDI/ArC), Ebean 19.2.0, Konsist, detekt, Kover.

**Spec:** `docs/specs/2026-08-03-structural-soft-delete-read-isolation.md`. **ADR:** `docs/adr/0008`.

## Global constraints

Copied verbatim from the spec and `agents/project.md`; every task's requirements implicitly include them.

- All repository content is English (identifiers, comments, commits, docs).
- Conventional commits: `refactor(persistence):`, `test(persistence):`, etc.
- Strict TDD: the failing test is committed alone before its implementation as `test(scope): <behaviour>`,
  its body carrying the red (the command run and its failure, pasted from that run). Behaviour-preserving
  refactors use existing tests as the net (TDD order exemption, not a safety-net exemption).
- A structural assertion arrives with the mutation that makes it fail, pasted in the commit that introduces it.
- 100% branch coverage per package inside the gate perimeter (`api-persistence-sqlite` included;
  `api-application`, where `ArchitectureKonsistTest` lives, is outside the coverage bound but the assertions
  still need their mutation-red).
- `!!` is forbidden; no em dash or en dash anywhere humans read.
- Test names use backticks and `Given..., Then...` (no "when"); bodies use `// Given`, `// When`, `// Then`.
- The gate: `./gradlew gate`. One test: `./gradlew :api-persistence-sqlite:test --tests "ClassName"`.
- Ebean query beans: the no-arg constructor resolves to the default `Database` under `defaultDatabase(true)`
  (verified, `build/generated/.../QImageModel.kt:45-46`, `EbeanDatabaseProducer.kt:21`).

## File structure

**Create:**
- `api-persistence-sqlite/.../persistence/sqlite/Persistor.kt`: write port interface (save/delete/merge/reference).
- `api-persistence-sqlite/.../persistence/sqlite/TransactionControl.kt`: transaction port interface.
- `api-persistence-sqlite/.../persistence/sqlite/repositories/EbeanPersistor.kt`: `Database`-wrapping impl.
- `api-persistence-sqlite/.../persistence/sqlite/repositories/EbeanTransactionControl.kt`: `Database`-wrapping impl.
- `api-persistence-sqlite/.../persistence/sqlite/repositories/EbeanPersistorTest.kt`: boundary tests.
- `api-persistence-sqlite/.../persistence/sqlite/repositories/EbeanTransactionControlTest.kt`: boundary tests.

**Modify:**
- `ModelRepository.kt`: drop `BeanRepository`, hold `Persistor`.
- 7 CRUD repos: `Database` to `Persistor`.
- `EbeanTransactionRunner.kt`, `EbeanTaskQueue.kt`, `EbeanImageRepository.kt`: add `TransactionControl`.
- `EbeanImageRepository.kt`, `EbeanImageDownloadRepository.kt`: drop the `database` arg from `QImage*`.
- `ArchitectureKonsistTest.kt`: two new assertions.

---

## Task 1: Persistor port and EbeanPersistor

**Files:**
- Create: `api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/Persistor.kt`
- Create: `api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/EbeanPersistor.kt`
- Test: `api-persistence-sqlite/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/EbeanPersistorTest.kt`

**Interfaces:**
- Produces: `Persistor { save(Any); delete(Any); merge(Any); <T> reference(Class<T>, id: Any): T }` and the
  `@ApplicationScoped class EbeanPersistor(database: Database) : Persistor`. Later tasks inject `Persistor`.

**Acceptance:** every `Persistor` method delegates to the wrapped `Database`; the class has 100% branch
coverage; the build compiles and the gate's coverage bound holds for its package. No consumer uses it yet.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.RepositoryTest
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QUserModel
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EbeanPersistorTest : RepositoryTest() {
    private val persistor = EbeanPersistor(database)

    @Test
    fun `Given a model, Then save persists it`() {
        // Given
        val model = newUser()

        // When
        persistor.save(model)

        // Then
        assertNotNull(QUserModel().id.equalTo(model.id).findOne())
    }

    @Test
    fun `Given a persisted model, Then delete removes it`() {
        // Given
        val model = newUser()
        persistor.save(model)

        // When
        persistor.delete(model)

        // Then
        assertNull(QUserModel().id.equalTo(model.id).findOne())
    }

    @Test
    fun `Given a modified model, Then merge writes the change`() {
        // Given
        val model = newUser()
        persistor.save(model)
        model.name = "renamed"

        // When
        persistor.merge(model)

        // Then
        assertEquals("renamed", QUserModel().id.equalTo(model.id).findOne()?.name)
    }

    @Test
    fun `Given a type and id, Then reference returns a usable proxy`() {
        // Given
        val model = newUser()
        persistor.save(model)

        // When
        val reference = persistor.reference(UserModel::class.java, model.id)

        // Then
        assertEquals(model.id, reference.id)
    }

    // UserModel is the root entity (no foreign key), so it is the simplest model to persist directly.
    private fun newUser(): UserModel =
        UserModel(id = UUID.randomUUID(), name = createRandomString(), createdAt = storableNow())
}
```

- [ ] **Step 2: Run the test to verify it fails (red)**

Run: `./gradlew :api-persistence-sqlite:test --tests "EbeanPersistorTest"`
Expected: `compileTestKotlin` fails with `unresolved reference: Persistor` and `EbeanPersistor`. Paste this
output into the next commit's body: it is the red.

- [ ] **Step 3: Commit the failing test alone**

```bash
git add api-persistence-sqlite/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/EbeanPersistorTest.kt
git commit -m "test(persistence): add Persistor boundary tests"   # body carries the compile red
```

- [ ] **Step 4: Write the port and its implementation**

```kotlin
// Persistor.kt
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

/**
 * Write capability over the database: persists, deletes, merges, and builds foreign-key references.
 * Exposes nothing that reads a row, so a holder cannot root an unfiltered query through it. The read
 * capability (the [io.ebean.Database] type) is confined to this module's producer and the two port
 * implementations; see ADR 0008.
 */
interface Persistor {
    fun save(bean: Any)
    fun delete(bean: Any)
    fun merge(bean: Any)
    fun <T : Any> reference(type: Class<T>, id: Any): T
}
```

```kotlin
// repositories/EbeanPersistor.kt
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.Persistor
import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class EbeanPersistor(
    private val database: Database,
) : Persistor {
    override fun save(bean: Any) {
        database.save(bean)
    }

    override fun delete(bean: Any) {
        database.delete(bean)
    }

    override fun merge(bean: Any) {
        database.merge(bean)
    }

    override fun <T : Any> reference(type: Class<T>, id: Any): T {
        return database.reference(type, id)
    }
}
```

Two Kotlin/Ebean interop points, both verified by compilation: block bodies (not expression)
because `Database.delete` returns a Java `boolean` an expression body would infer as the override's
return type; and the `: Any` bound on `reference`, because Ebean's `Database.reference` is
non-null-bounded in Kotlin, so the unbounded `<T>` does not compile (`Class<T>` vs `Class<T & Any>`).

- [ ] **Step 5: Run the test to verify it passes (green)**

Run: `./gradlew :api-persistence-sqlite:test --tests "EbeanPersistorTest"`
Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/Persistor.kt \
        api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/EbeanPersistor.kt
git commit -m "feat(persistence): add Persistor write port and Ebean impl"
```

---

## Task 2: TransactionControl port and EbeanTransactionControl

**Files:**
- Create: `api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/TransactionControl.kt`
- Create: `api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/EbeanTransactionControl.kt`
- Test: `api-persistence-sqlite/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/EbeanTransactionControlTest.kt`

**Interfaces:**
- Produces: `TransactionControl { beginTransaction(): io.ebean.Transaction; currentTransaction(): io.ebean.Transaction? }`
  and the `@ApplicationScoped class EbeanTransactionControl(database: Database) : TransactionControl`.

**Acceptance:** both methods delegate; 100% branch coverage; build green; no consumer yet.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.RepositoryTest
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class EbeanTransactionControlTest : RepositoryTest() {
    private val transactionControl = EbeanTransactionControl(database)

    @Test
    fun `Given no ambient transaction, Then currentTransaction is null`() {
        // Given no transaction opened on this thread
        // When
        val current = transactionControl.currentTransaction()

        // Then
        assertNull(current)
    }

    @Test
    fun `Given beginTransaction, Then it returns a transaction that currentTransaction sees`() {
        // Given
        transactionControl.beginTransaction().use { transaction ->
            // When
            val current = transactionControl.currentTransaction()

            // Then
            assertNotNull(current)
            transaction.commit()
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails (red)**

Run: `./gradlew :api-persistence-sqlite:test --tests "EbeanTransactionControlTest"`
Expected: `compileTestKotlin` fails with `unresolved reference: TransactionControl` / `EbeanTransactionControl`.
Paste into the commit body.

- [ ] **Step 3: Commit the failing test alone**

```bash
git add api-persistence-sqlite/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/EbeanTransactionControlTest.kt
git commit -m "test(persistence): add TransactionControl boundary tests"
```

- [ ] **Step 4: Write the port and its implementation**

```kotlin
// TransactionControl.kt
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import io.ebean.Transaction

/**
 * Transaction-lifecycle capability: opens and inspects transactions. Carries no read and no write, so a
 * holder can scope work atomically without rooting a query. Lower-level than the domain
 * `TransactionRunner.inTransaction { }`, which stays the use-case-facing abstraction.
 */
interface TransactionControl {
    fun beginTransaction(): Transaction
    fun currentTransaction(): Transaction?
}
```

```kotlin
// repositories/EbeanTransactionControl.kt
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.TransactionControl
import io.ebean.Database
import io.ebean.Transaction
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class EbeanTransactionControl(
    private val database: Database,
) : TransactionControl {
    override fun beginTransaction(): Transaction = database.beginTransaction()
    override fun currentTransaction(): Transaction? = database.currentTransaction()
}
```

- [ ] **Step 5: Run the test to verify it passes (green)**

Run: `./gradlew :api-persistence-sqlite:test --tests "EbeanTransactionControlTest"`
Expected: PASS, 2 tests.

- [ ] **Step 6: Commit**

```bash
git add api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/TransactionControl.kt \
        api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/EbeanTransactionControl.kt
git commit -m "feat(persistence): add TransactionControl port and Ebean impl"
```

---

## Task 3: Rewrite ModelRepository and switch the 7 CRUD repositories to Persistor

This is a behaviour-preserving refactor; the existing repository tests are the safety net (no new failing
test). `ModelRepository` stops extending `BeanRepository`, and its seven instantiators switch from
`Database` to `Persistor`.

**Files:**
- Modify: `api-persistence-sqlite/.../repositories/ModelRepository.kt`
- Modify: `BoardRepository.kt`, `PinRepository.kt`, `UserRepository.kt`, `TagRepository.kt`,
  `SessionTokenRepository.kt`, `UserDataExportRepository.kt`, `UserPasswordHashRepository.kt`

**Interfaces:**
- Consumes: `Persistor` (Task 1).
- Produces: `ModelRepository(persistor: Persistor)`; the 7 repos now inject `Persistor`.

**Acceptance:** no class extends `BeanRepository`; `ModelRepository` exposes only `saveAndReturn`; the 7 repos
no longer import `io.ebean.Database`; all existing repository tests green.

- [ ] **Step 1: Rewrite ModelRepository**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.Persistor
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.BaseModel

internal class ModelRepository<T : BaseModel>(
    private val persistor: Persistor,
) {
    fun saveAndReturn(model: T): T = model.also { persistor.merge(it) }
}
```

The `entityClass: KClass<T>` parameter and the `BeanRepository` supertype are gone; `merge` resolves the
descriptor from the bean's class, so `entityClass` was never needed. Without it to anchor `T`, each
construction states the type argument explicitly (`ModelRepository<BoardModel>(persistor = persistor)`).

- [ ] **Step 2: Switch BoardRepository (template for the others)**

Constructor and field:

```kotlin
// import io.ebean.Database  -> DELETE this import
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.Persistor

@Suppress("TooManyFunctions")
class BoardRepository(
    private val persistor: Persistor,
) : BoardRepositoryInterface {
    private val sqlRepository = ModelRepository<BoardModel>(persistor = persistor)
```

Direct writes: `database.save(model)` becomes `persistor.save(model)` at `BoardRepository.kt:55` (softDelete)
and `:65` (restore). Everything else (the `BoardQueries` and `QPinBoardModel` reads) is unchanged.

- [ ] **Step 3: Apply the same transformation to the remaining six, at these exact sites**

| Repository | Constructor param | `ModelRepository(...)` | Direct `database.*` calls to move |
|---|---|---|---|
| `PinRepository.kt:42-46` | `database: Database` to `persistor: Persistor` | `ModelRepository(persistor = persistor)` | `database.save(it)`/`database.save(model)` at `:110, :144, :202, :212` to `persistor.save(...)` |
| `UserRepository.kt:24` | `database` to `persistor` | `ModelRepository(persistor = persistor)` | `database.save(model)` `:65` to `persistor.save`; `database.delete(model)` `:74` to `persistor.delete` |
| `TagRepository.kt:18` | `database` to `persistor` | `ModelRepository(persistor = persistor)` | none |
| `SessionTokenRepository.kt:18` | `database` to `persistor` | `ModelRepository(persistor = persistor)` | none |
| `UserDataExportRepository.kt:34` | `database` to `persistor` | `ModelRepository(persistor = persistor)` | `database.reference(UserModel::class.java, export.userId)` `:51` to `persistor.reference(UserModel::class.java, export.userId)` |
| `UserPasswordHashRepository.kt:21` | `database` to `persistor` | `ModelRepository(persistor = persistor)` | none |

For each: delete the `import io.ebean.Database` line, add `import ...persistence.sqlite.Persistor`, change
the constructor parameter, change the `ModelRepository(...)` construction to anchor `T` with the repo's model
type (for example `ModelRepository<PinModel>(persistor = persistor)`), and move the listed direct calls.
Leave every `Q*Model()` read and write construction untouched: the CRUD repos use only no-arg query beans.

- [ ] **Step 4: Run the repository tests (safety net)**

Run: `./gradlew :api-persistence-sqlite:test`
Expected: PASS, all existing repository tests green, coverage bound holds.

- [ ] **Step 5: Commit**

```bash
git add api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/ModelRepository.kt \
        api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/BoardRepository.kt \
        api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/PinRepository.kt \
        api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/UserRepository.kt \
        api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/TagRepository.kt \
        api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/SessionTokenRepository.kt \
        api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/UserDataExportRepository.kt \
        api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/UserPasswordHashRepository.kt
git commit -m "refactor(persistence): ModelRepository holds Persistor, CRUD repos switch to it"
```

---

## Task 4: EbeanTransactionRunner to TransactionControl

**Files:**
- Modify: `api-persistence-sqlite/.../repositories/EbeanTransactionRunner.kt`

**Interfaces:** Consumes `TransactionControl` (Task 2).

**Acceptance:** `EbeanTransactionRunner` no longer imports `Database`; `inTransaction` behaves unchanged.

- [ ] **Step 1: Replace the dependency**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.TransactionControl
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class EbeanTransactionRunner(
    private val transactionControl: TransactionControl,
) : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T =
        transactionControl.beginTransaction().use { transaction ->
            val result = block()
            transaction.commit()
            result
        }
}
```

- [ ] **Step 2: Run the use-case tests that exercise transactions**

Run: `./gradlew :api-usecases:test :api-application:test`
Expected: PASS (transactional use cases: `UserCreator`, `PasswordChanger`, `SessionCreator`, etc.).

- [ ] **Step 3: Commit**

```bash
git add api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/EbeanTransactionRunner.kt
git commit -m "refactor(persistence): EbeanTransactionRunner uses TransactionControl"
```

---

## Task 5: EbeanTaskQueue to Persistor + TransactionControl

**Files:**
- Modify: `api-persistence-sqlite/.../repositories/EbeanTaskQueue.kt`

**Interfaces:** Consumes `Persistor` and `TransactionControl`.

**Acceptance:** writes go through `Persistor`, transaction boundaries through `TransactionControl`; every
`QTaskModel(database)` construction becomes `QTaskModel()`; task-queue tests green.

- [ ] **Step 1: Switch the dependency and call sites**

- Constructor: `private val database: Database` becomes two params:
  `private val persistor: Persistor, private val transactionControl: TransactionControl`.
- Imports: drop `import io.ebean.Database`; add `import ...persistence.sqlite.Persistor` and
  `import ...persistence.sqlite.TransactionControl`.
- `database.beginTransaction()` at `:48` and `:88` to `transactionControl.beginTransaction()`.
- `database.currentTransaction()` at `:45` to `transactionControl.currentTransaction()`.
- `database.save(model)` at `:76, :110, :119` to `persistor.save(model)`.
- Every `QTaskModel(database)` construction (9 sites: `:58, :80, :82, :90, :204, :214, :223, :235, :244`)
  to `QTaskModel()`. Like the image query beans, the explicit `database` is redundant under
  `defaultDatabase(true)`.

- [ ] **Step 2: Run the task-queue tests**

Run: `./gradlew :api-persistence-sqlite:test --tests "EbeanTaskQueue*"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/EbeanTaskQueue.kt
git commit -m "refactor(persistence): EbeanTaskQueue uses Persistor and TransactionControl"
```

---

## Task 6: EbeanImageDownloadRepository to Persistor (drop the database arg)

**Files:**
- Modify: `api-persistence-sqlite/.../repositories/EbeanImageDownloadRepository.kt`

**Interfaces:** Consumes `Persistor`.

**Acceptance:** no `Database` import; `QImageDownloadModel(database)` becomes `QImageDownloadModel()`;
writes via `Persistor`; image-download tests green.

- [ ] **Step 1: Switch dependency and drop the redundant argument**

- Constructor: `private val database: Database` to `private val persistor: Persistor`.
- Imports: drop `import io.ebean.Database`; add `import ...persistence.sqlite.Persistor`.
- `QImageDownloadModel(database)` at `:22, :32, :52, :56` to `QImageDownloadModel()` (default database, same
  instance under `defaultDatabase(true)`).
- `database.save(model)` at `:27` to `persistor.save(model)`.

- [ ] **Step 2: Run the image-download tests**

Run: `./gradlew :api-persistence-sqlite:test --tests "EbeanImageDownloadRepositoryTest"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/EbeanImageDownloadRepository.kt
git commit -m "refactor(persistence): EbeanImageDownloadRepository uses Persistor"
```

---

## Task 7: EbeanImageRepository to Persistor + TransactionControl (drop the database arg)

**Files:**
- Modify: `api-persistence-sqlite/.../repositories/EbeanImageRepository.kt`

**Interfaces:** Consumes `Persistor` and `TransactionControl`.

**Acceptance:** no `Database` import; `QImageModel(database)` becomes `QImageModel()`; writes via `Persistor`;
ambient-transaction check via `TransactionControl`; image tests green. After this task only
`EbeanDatabaseProducer`, `EbeanPersistor` and `EbeanTransactionControl` import `io.ebean.Database`.

- [ ] **Step 1: Switch dependency, transaction, and the redundant argument**

- Constructor: `private val database: Database` to `private val persistor: Persistor, private val transactionControl: TransactionControl`.
- Imports: drop `import io.ebean.Database`; add `import ...persistence.sqlite.Persistor` and
  `import ...persistence.sqlite.TransactionControl`.
- `database.currentTransaction()` at `:21` to `transactionControl.currentTransaction()`.
- `database.beginTransaction()` at `:24` to `transactionControl.beginTransaction()`.
- `QImageModel(database)` at `:32, :39, :42, :47` to `QImageModel()`.
- `database.save(model)` at `:34` to `persistor.save(model)`.

- [ ] **Step 2: Run the image tests**

Run: `./gradlew :api-persistence-sqlite:test --tests "EbeanImageRepositoryTest"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/EbeanImageRepository.kt
git commit -m "refactor(persistence): EbeanImageRepository uses Persistor and TransactionControl"
```

---

## Task 8: Konsist assertion, Database confined (D3)

**Files:**
- Modify: `api-application/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/application/ArchitectureKonsistTest.kt`

**Acceptance:** the assertion holds that `io.ebean.Database` is imported only in `EbeanDatabaseProducer`,
`EbeanPersistor` and `EbeanTransactionControl`. The path patterns need the `..` wildcard prefix: Konsist
anchors a bare name with an end match and misses the `.kt` suffix (verified against Konsist 0.17.3). Step 3
confirms the three sanctioned files are excluded. Introduced with a mutation red.

- [ ] **Step 1: Add the assertion**

Append to `ArchitectureKonsistTest`:

```kotlin
@Test
fun `Given production sources, Then io_ebean Database is confined to its sanctioned homes`() {
    Konsist
        .scopeFromProduction()
        .files
        .withImport { it.name == "io.ebean.Database" }
        .withoutPath("..EbeanDatabaseProducer.kt", "..EbeanPersistor.kt", "..EbeanTransactionControl.kt")
        .assertEmpty()
}
```

- [ ] **Step 2: Prove it holds something, by mutation**

Temporarily re-add `import io.ebean.Database` to `BoardRepository.kt` (or any repo outside the allowlist).
Run: `./gradlew :api-application:test --tests "ArchitectureKonsistTest"`
Expected: FAIL, reporting `BoardRepository.kt` as a violator. Paste this failure into the commit body. Then
revert the mutation (`git checkout -- BoardRepository.kt`).

- [ ] **Step 3: Confirm it passes on the real tree**

Run: `./gradlew :api-application:test --tests "ArchitectureKonsistTest"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add api-application/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/application/ArchitectureKonsistTest.kt
git commit -m "test(architecture): confine io.ebean.Database to its sanctioned homes"   # body carries the mutation red
```

---

## Task 9: Konsist assertion, no Ebean bean finder (D4)

**Files:**
- Modify: `api-application/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/application/ArchitectureKonsistTest.kt`

**Acceptance:** no production class has `BeanRepository` or `BeanFinder` as its parent class. Introduced
with a mutation red.

- [ ] **Step 1: Add the assertion**

Append to `ArchitectureKonsistTest`, with the banned names as a named set:

```kotlin
private val ebeanBeanFinderSupertypes = setOf("BeanRepository", "BeanFinder")

@Test
fun `Given production sources, Then no class extends an Ebean bean finder`() {
    Konsist
        .scopeFromProduction()
        .classes()
        .withParent { parent ->
            parent.name.substringBefore("<").substringAfterLast(".") in ebeanBeanFinderSupertypes
        }
        .assertEmpty()
}
```

`withParent` matches the parent's bare simple name (stripping generics and package prefix), so it catches
both an imported `BeanRepository<...>` and a fully-qualified `io.ebean.BeanRepository<...>` written with no
import (the case an import ban would miss). NOTE: the obvious `withParentClassOf(...)` is a silent no-op in
Konsist 0.17.3 for external-library types: `parentClasses()` filters through `sourceDeclaration?.isClass`,
and external types resolve to `KoExternalDeclarationCore`, not `KoClassDeclaration`, so they are dropped and
the assertion passes whether or not a violation exists. The mutation-red caught this; `withParent` is the
working form. (Accepted over-breadth: a project class literally named `BeanRepository` would trip it.)

- [ ] **Step 2: Prove it holds something, by mutation**

Temporarily make a production class extend `BeanRepository` (for instance add
` : io.ebean.BeanRepository<java.util.UUID, fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.TagModel>`
to a throwaway class). Run: `./gradlew :api-application:test --tests "ArchitectureKonsistTest"`
Expected: FAIL, reporting that class. Paste into the commit body, then revert the mutation.

- [ ] **Step 3: Confirm it passes on the real tree**

Run: `./gradlew :api-application:test --tests "ArchitectureKonsistTest"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add api-application/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/application/ArchitectureKonsistTest.kt
git commit -m "test(architecture): bar Ebean bean finders as production supertypes"
```

---

## Task 10: Full gate green

**Acceptance:** `./gradlew gate` is green (detekt with type resolution, all tests, the 100% branch coverage
bound, `checkNoLongDashes`). The authentication end-to-end test (a tombstoned account cannot obtain a
session) is green, proving the security-bearing read path was not disturbed.

- [ ] **Step 1: Run the gate**

Run: `./gradlew gate`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Verify the confinement by inspection**

Run: `rg -l "import io.ebean\.Database" --glob '**/src/main/**' --glob '!**/build/**'`
Expected: exactly three files: `EbeanDatabaseProducer.kt`, `EbeanPersistor.kt`, `EbeanTransactionControl.kt`.

Run: `rg "BeanRepository|BeanFinder" --glob '**/src/main/**' --glob '!**/build/**'`
Expected: no output.

Run: `rg "Q\w+Model\(database\)" --glob '**/src/main/**' --glob '!**/build/**'`
Expected: no output (every query bean now uses its no-arg constructor).

- [ ] **Step 3: No commit (verification only); the tree is already clean from Task 9**

---

## Task 11: Detekt rule banning the Ebean static read facades (D5, FQN-proof)

The holistic review found that `io.ebean.DB` (and the deprecated `io.ebean.Ebean`) are static read facades
over the default `Database`: a production class can write `io.ebean.DB.find(BoardModel::class.java, id)` and
read a recyclable row unfiltered, passing D3/D4 (D3 confines the `Database` instance; the facade needs none).
D5 closes it: a detekt rule (FQN-proof, the `QueryBeanConstructedByQualifiedName` analog) plus a Konsist
import ban (Task 12).

**Files:**
- Create: `detekt-rules/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/detekt/DatabaseStaticFacadeCall.kt`
- Test: `detekt-rules/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/detekt/DatabaseStaticFacadeCallTest.kt`
- Modify: `detekt-rules/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/detekt/PinryRuleSetProvider.kt` (register), `config/detekt/detekt.yml` (activate, production scope).

**Goal:** report any production call on `io.ebean.DB` or `io.ebean.Ebean`, whether written `DB.find(...)`
(imported) or `io.ebean.DB.find(...)` (fully-qualified, no import). Tests are exempt: `RepositoryTest` uses
`DB.getDefault()`.

**Acceptance:** fires on a violating snippet and on a real production mutation; silent on compliant code and on test sources; registered and activated; 100% branch coverage via `detekt-test`'s `lint()`.

- [ ] **Step 1: Write the failing tests** (`detekt-test` `lint()`). Snippets that each report one finding:
  `io.ebean.DB.find(Any::class.java)` (FQN) and (with `import io.ebean.DB`) `DB.find(Any::class.java)`;
  plus the same for `Ebean`. A snippet using the injected `Database` (`database.find(...)`) reports none.
  Run `./gradlew :detekt-rules:test --tests "DatabaseStaticFacadeCallTest"` -> `compileTestKotlin` fails
  (rule absent). Paste the red. Commit `test(detekt): DatabaseStaticFacadeCall cases` (test alone).
- [ ] **Step 2: Implement the rule.** A `Rule` visiting qualified expressions, reporting when the receiver
  resolves to `io.ebean.DB`/`io.ebean.Ebean` (FQN text) or to a simple name `DB`/`Ebean` the file imports
  as `io.ebean.DB`/`io.ebean.Ebean`. Follow `SoftDeleteStateFilteredOutsideQueries` and
  `QueryBeanConstructedByQualifiedName` for structure (companion config key, `report(Finding(...))`). Run the
  tests -> green. Commit `feat(detekt): ban Ebean static read facades`.
- [ ] **Step 3: Register and configure.** Add the rule in `PinryRuleSetProvider`; activate it in
  `config/detekt/detekt.yml` under the `pinry-reborn` set, scoped to production (excludes `**/test/**`,
  `**/testFixtures/**`, like the existing rules; it applies to every module's main sources).
- [ ] **Step 4: Mutation-red.** Temporarily add `val x = io.ebean.DB.find(fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserModel::class.java)` to a production file; run `./gradlew detekt` -> FAIL naming the rule and site; paste verbatim into the commit body; revert; confirm green.

## Task 12: Konsist D5 import ban + D4 style fixes

**Files:** Modify `api-application/src/test/kotlin/.../ArchitectureKonsistTest.kt`.

- [ ] **Step 1: Add D5** (catches the imported form; Task 11's rule catches FQN):

```kotlin
@Test
fun `Given production sources, Then nothing imports an Ebean static facade`() {
    Konsist
        .scopeFromProduction()
        .files
        .withImport { it.name in setOf("io.ebean.DB", "io.ebean.Ebean") }
        .assertEmpty()
}
```

Mutation-red: add `import io.ebean.DB` to a production file; run `./gradlew :api-application:test --tests "ArchitectureKonsistTest"` -> FAIL; paste verbatim; revert; green.

- [ ] **Step 2: D4 style.** Trim the D4 comment to at most two lines with a pointer to ADR 0008 / commit
  `0ea264d` for the `withParentClassOf` Konsist gotcha (the detail moves to the ADR, Task 14 docs). Add
  `.trim()` to the parent-name extraction: `parent.name.substringBefore("<").substringAfterLast(".").trim()`.
- [ ] **Step 3: Commit** `test(architecture): ban Ebean static facade imports, tighten D4` (body carries the mutation-red).

## Task 13: Full gate green + confinement inspections

- [ ] **Step 1:** `./gradlew gate` -> BUILD SUCCESSFUL.
- [ ] **Step 2:** Inspections, each expected as stated:
  - `rg -l "import io.ebean\.Database" --glob '**/src/main/**' --glob '!**/build/**'` -> exactly `EbeanDatabaseProducer.kt`, `EbeanPersistor.kt`, `EbeanTransactionControl.kt`.
  - `rg "BeanRepository|BeanFinder" --glob '**/src/main/**' --glob '!**/build/**'` -> none.
  - `rg "Q\w+Model\(database\)" --glob '**/src/main/**' --glob '!**/build/**'` -> none.
  - `rg "io\.ebean\.(DB|Ebean)" --glob '**/src/main/**' --glob '!**/build/**'` -> none.
- [ ] **Step 3:** `git status --porcelain` empty. No commit (verification).

---

## Notes for the implementer

- The 7 CRUD repos and the 4 Ebean adapters are `@ApplicationScoped` CDI beans; Quarkus ArC does implicit
  constructor injection for single-constructor beans (precedent: `EbeanTransactionRunner`). No `@Inject`
  annotation is needed.
- `Persistor` and `TransactionControl` are public interfaces in `...persistence.sqlite` (not `internal`):
  a public CDI bean cannot expose an `internal`-typed constructor parameter, so "persistence-internal" is a
  conceptual boundary here, enforced structurally by D3 (the `Database` confinement) rather than by Kotlin
  visibility.
- The Konsist `withImport` lambda gives `it.name` as the fully-qualified import name (see the existing
  assertion at `ArchitectureKonsistTest.kt:162-176`, which uses `it.name.substringAfterLast(".")`).

## Out of scope, to file in the backlog at wrap

- Unifying the ambient-transaction logic of `EbeanTaskQueue` and `EbeanImageRepository` through
  `TransactionRunner` (Ebean nesting behaviour, risky).
- Routes 2 (raw SQL predicate on `any()`) and 3 (in-memory read after `any()`), which 0007 recorded and
  this pass leaves open.
