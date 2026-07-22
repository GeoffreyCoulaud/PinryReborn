# User data export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an authenticated user request, track and download a self-contained archive of all their data, following `docs/specs/2026-07-22-user-data-export.md`.

**Architecture:** Hexagonal. A new `ExportArchiveStore` domain port owns archive production (stage into a temp file, measure size + SHA-256, fsync, promote by atomic rename) and declares the container format it produces; ZIP and Jackson mechanics live in `api-storage-filesystem`. Building is asynchronous on the task queue (`account.export`); metadata lives in `user_data_exports` (bytes on disk, never in the database); the download endpoint reads media type, size and extension back from the row through a non-nullable projection.

**Tech Stack:** Kotlin, Quarkus 3 (Jakarta REST), Ebean 19 + SQLite, `java.util.zip`, Jackson (adapter only), JUnit 5, MockK, REST Assured, Kover (100% branch coverage).

## Global Constraints

- **100% branch coverage per package**, gated by `koverVerify`. Exercise both sides of every conditional.
- **Strict TDD**: write the failing test first, watch it fail, then the minimal implementation. **A Kotlin `@Test` with an empty body compiles and PASSES** — every test below must be written with its body before running the "watch it fail" step, or that step is a lie.
- **Clean/Hexagonal**: `api-usecases` depends only on `api-domain`. Konsist (`ArchitectureKonsistTest`) bans `io.ebean`, `jakarta.transaction`, `jakarta.ws.rs` and `org.mindrot` from `api-usecases`, and restricts `api-domain` imports to its own packages plus `java.time.Instant`/`Duration`, `java.util.UUID`, `java.io.InputStream`. **Verified: the new domain port needs no widening of that allowlist.**
- **Assertions**: `kotlin.test` is NOT on any module's test classpath. Use `org.junit.jupiter.api.Assertions.*` (`assertEquals`, `assertNull`, `assertTrue`, `assertThrows`, `assertArrayEquals`). There is no `assertContentEquals`.
- **Language: English everywhere.** **Conventional commits.** **No top-level functions.**
- **Test naming**: `` `Given ..., Then ...` ``; bodies carry `// Given` / `// When` / `// Then`.
- **Test bases**: integration → `IntegrationTest` (+ `@QuarkusTest`); use-case → `BaseTest` (MockK; `checkUnnecessaryStub()` in `@AfterEach`, so a stub must be exercised **by the test that declares it** — never put an `inTransaction` passthrough in `@BeforeEach` when some tests throw before the transaction); repository → `RepositoryTest`.
- **Repository test helpers are per-class**: `createAndSaveUser()` is a private method duplicated in each repository test, and repositories take the `Database` (`UserDataExportRepository(database)`).
- **Repository tests live in the package root** `fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite`, not under `repositories/` (only `SessionTokenRepositoryTest` does).
- **Worker package** is `fr.geoffreyCoulaud.pinryReborn.api.worker` (no `.quarkus` suffix).
- **`@Suppress("LongParameterList")`** on constructors above 6 parameters, following `TaskModel`.
- **Run the gate** with `./gradlew check koverVerify` (JDK 25 as the default JVM, libvips installed).

## This plan supersedes the spec on nothing

Both were revised together after the adversarial review. Where an earlier draft said `api.exports.*`
or `EXPORT_NOT_FOUND`, the current spec says `exports.*` (snake case) and `EXPORT_DOES_NOT_EXIST`.

## File Structure

**New files**

- `api-domain/.../domain/storage/StagedFile.kt` (moved), `.../domain/exports/ExportArchiveStore.kt`, `.../domain/entities/UserDataExport.kt`, `.../domain/enums/UserDataExportState.kt`, `.../domain/repositories/UserDataExportRepositoryInterface.kt`
- `api-persistence-sqlite/.../models/UserDataExportModel.kt`, `.../mappers/UserDataExportModelMapper.kt`, `.../repositories/UserDataExportRepository.kt`, `dbmigration/1.10.sql`, `dbmigration/1.11.sql` (hand-written index)
- `api-storage-filesystem/.../CountingDigestOutputStream.kt`, `.../ZipArchiveSink.kt`, `.../FilesystemZipExportArchiveStore.kt`
- `api-usecases/.../usecases/exports/` : `ExportContent.kt`, `ExportImageExtension.kt`, `ExportReadme.kt`, `UserDataExportRequester.kt`, `UserDataExportBuilder.kt`, `UserDataExportGetter.kt`, `UserDataExportDownloader.kt`, `UserDataExportDeleter.kt`, `ReapExpiredUserDataExports.kt`
- `api-usecases/.../usecases/tasks/UserDataExportTask.kt`, `.../exceptions/UserDataExportError.kt`
- `api-worker-quarkus/.../UserDataExportTaskHandler.kt`, `.../ExportRetentionLifecycle.kt`, `.../ExportsConfig.kt`
- `api-application/.../wiring/ExportProducers.kt`
- `api-presentation-quarkus/.../controllers/MeExportController.kt`, `.../dtos/output/UserDataExportOutputDto.kt`, `.../mappers/UserDataExportDtoMapper.kt`, `.../http/RangeHeader.kt`, `.../http/ContentDispositionFileName.kt`

**Modified files**

- `api-persistence-sqlite/.../pagination/PinModelSortStrategy.kt` (Task 0a)
- `api-domain/.../repositories/TaskQueueInterface.kt`, `api-persistence-sqlite/.../repositories/EbeanTaskQueue.kt` (Task 0b)
- `api-domain/.../entities/{User,Pin,Board,Tag}.kt`, the four model mappers (Task 1)
- `api-domain/.../images/{ImageStore,RenditionCache,ImageTransformer}.kt` + adapters (Task 2)
- `api-domain/.../repositories/PinRepositoryInterface.kt` + `PinRepository` (Task 3: unfiltered memberships)
- `api-usecases/.../exceptions/ErrorCode.kt`, `api-presentation-quarkus/.../mappers/BaseErrorMapper.kt` (Task 6, same commit)
- `api-usecases/.../AccountDeletionCleaner.kt` (Task 12)
- `gradle/libs.versions.toml`, `api-storage-filesystem/build.gradle.kts` (Task 5)
- `api-application/src/main/resources/application.properties` (Task 10)

---

### Task 0a: Give cursor pagination a tie-breaker (pre-existing bug)

Latent today, fatal for an export: `PinModelSortStrategy` filters and orders on `whenCreated` alone,
so if more than one page of pins shares a timestamp the cursor never advances. The existing API shows
a stuck page; a drained cursor writes an unbounded archive.

**Files:**
- Modify: `api-persistence-sqlite/src/main/kotlin/.../pagination/PinModelSortStrategy.kt`
- Test: `api-persistence-sqlite/src/test/kotlin/.../PinRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `Given more pins than a page sharing one creation instant, Then paging reaches them all`() {
    // Given
    val user = createAndSaveUser()
    val instant = Instant.parse("2026-07-22T10:00:00Z")
    val ids = (1..5).map { createAndSavePinAt(user, instant).id }.toSet()

    // When
    val seen = mutableSetOf<UUID>()
    var cursor: Cursor? = null
    var pages = 0
    do {
        val page = pinRepository.findPinsForUser(user, cursor, 2, PinSortStrategy.CREATED_AT_DESC)
        seen += page.items.map { it.id }
        cursor = page.nextCursor
        pages++
    } while (cursor != null && pages < 10)

    // Then
    assertEquals(ids, seen)
    assertTrue(pages < 10, "pagination did not terminate")
}
```

`createAndSavePinAt` forces `whenCreated` with a raw SQL update after saving (`@WhenCreated` is
Ebean-managed): `database.sqlUpdate("update pins set when_created = ? where id = ?")`.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :api-persistence-sqlite:test --tests "*PinRepositoryTest*"`
Expected: FAIL, `seen` holds 2 ids and the loop hits its 10-page guard.

- [ ] **Step 3: Add the id tie-breaker**

In each strategy, make the keyset `(sortColumn, id)`: the predicate becomes
`whenCreated < pivot OR (whenCreated = pivot AND id < pivotId)` for a descending sort (mirror for
ascending, and for `DELETED_AT_DESC` on `softDeletedAt`), and the ordering gains `.id.desc()`.

- [ ] **Step 4: Run the whole module, then commit**

Run: `./gradlew :api-persistence-sqlite:test`
Expected: PASS (existing pagination tests included).

```bash
git commit -m "fix(persistence): break cursor pagination ties by id so paging always advances"
```

---

### Task 0b: Stop the queue from re-claiming a task forever (pre-existing bug)

`tasks.lease_duration` is `PT1M` and the reaper runs every 30 s, so a handler running longer than a
minute is re-claimed by another worker while still running. `claimNext` never compares `attempts` to
`maxAttempts`, and a handler that never returns never reaches `settle`, so the task never dies.

**Files:**
- Modify: `api-domain/.../repositories/TaskQueueInterface.kt`, `api-persistence-sqlite/.../repositories/EbeanTaskQueue.kt`
- Test: `api-persistence-sqlite/src/test/kotlin/.../EbeanTaskQueueTest.kt`

**Interfaces:**
- Produces: `TaskQueueInterface.renewLease(id: UUID, leaseId: String, until: Instant): Boolean`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `Given a task that exhausted its attempts, Then claiming marks it dead instead of running it`() {
    // Given
    val task = queue.enqueue(NewTask("k", "p", now, 0, maxAttempts = 1, null))
    queue.claimNext(now, lease)          // attempts becomes 1
    queue.reapExpired(now.plusSeconds(120))   // back to PENDING, still attempts = 1

    // When
    val claimed = queue.claimNext(now.plusSeconds(121), lease)

    // Then
    assertNull(claimed)
    assertEquals(TaskState.DEAD, queue.findById(task.id)?.state)
}

@Test
fun `Given a held lease, Then renewing it pushes the expiry back`() { ... }

@Test
fun `Given a stale lease id, Then renewing it fails and changes nothing`() { ... }
```

- [ ] **Step 2: Run and watch them fail**

Run: `./gradlew :api-persistence-sqlite:test --tests "*EbeanTaskQueueTest*"`

- [ ] **Step 3: Implement**

In `claimNext`, before leasing: if `model.attempts >= model.maxAttempts`, mark it `DEAD` with
`"attempts exhausted"` and return null (loop to the next candidate is unnecessary: the poller calls
again). Add `renewLease` as a fenced update, mirroring `markSucceeded`'s `leaseGuard(id, leaseId)`.

- [ ] **Step 4: Run the gate, then commit**

```bash
git commit -m "fix(persistence): never re-claim an exhausted task, and allow lease renewal"
```

---

### Task 1: Promote creation timestamps into the domain

Nullable with a `null` default: `null` means "not read from persistence". A non-null default would
need a clock in the domain; making them non-null would force every existing construction site to
invent a timestamp.

**Files:**
- Modify: `api-domain/.../entities/{User,Pin,Board,Tag}.kt`
- Modify: `api-persistence-sqlite/.../mappers/{User,Pin,Board,Tag}ModelMapper.kt`
- Test: `api-persistence-sqlite/src/test/kotlin/.../PinRepositoryTest.kt` (+ board, tag, user)

**Interfaces:**
- Produces: `User.createdAt`, `Pin.createdAt`/`updatedAt`, `Board.createdAt`/`updatedAt`, `Tag.createdAt`, all `Instant?`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `Given a saved pin, Then reading it back exposes its timestamps`() {
    // Given
    val pin = createAndSavePin()

    // When
    val found = pinRepository.findPinById(pin.id)

    // Then
    assertNotNull(found?.createdAt)
    assertNotNull(found?.updatedAt)
}
```

- [ ] **Step 2: Run and watch it fail** (`unresolved reference: createdAt`)

- [ ] **Step 3: Add the fields, map them in `toDomain` only**

`@WhenCreated`/`@WhenModified` are Ebean-managed; never write them in `toModel`.

**Pitfall:** `BaseModel.whenCreated` is `lateinit`. On a partial row (the soft-deleted-author case),
reading it throws `UninitializedPropertyAccessException` instead of the previously known NPE. Same
race, different symptom — record it in the handoff (Task 14). Do **not** add a defensive
`isInitialized` check: it would be an uncoverable branch.

- [ ] **Step 4: Run `./gradlew :api-persistence-sqlite:test`, then commit**

```bash
git commit -m "feat(domain): carry creation timestamps on user, pin, board and tag"
```

---

### Task 2: Move `StagedFile` to `domain.storage`

**Files:** create `api-domain/.../domain/storage/StagedFile.kt`; remove the declaration from
`ImageStore.kt`; fix every reference.

- [ ] **Step 1: Create the file**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.storage

/** Opaque local staging reference plus measured size and content hash. */
data class StagedFile(val path: String, val byteSize: Long, val contentHash: String)
```

- [ ] **Step 2: Fix every reference with one grep**

Run: `grep -rln "StagedFile" --include=*.kt .` — same-package users (`ImageStore`, `RenditionCache`,
`ImageTransformer`) have no import at all and would be missed by an import-only grep;
`api-storage-filesystem` does import it.

- [ ] **Step 3: `./gradlew check koverVerify`, then commit**

```bash
git commit -m "refactor(domain): move StagedFile to domain.storage, shared by images and exports"
```

---

### Task 3: Export entity, state, repository port, unfiltered memberships

**Files:**
- Create: `api-domain/.../enums/UserDataExportState.kt`, `.../entities/UserDataExport.kt`, `.../repositories/UserDataExportRepositoryInterface.kt`
- Modify: `api-domain/.../repositories/PinRepositoryInterface.kt`, `api-persistence-sqlite/.../repositories/PinRepository.kt`
- Test: `api-domain/src/test/kotlin/.../enums/UserDataExportStateTest.kt`, `api-persistence-sqlite/src/test/kotlin/.../PinRepositoryTest.kt`

- [ ] **Step 1: Write the enum and entity**

```kotlin
enum class UserDataExportState {
    PENDING, READY, FAILED, EXPIRED, DELETED, SUPERSEDED,
    ;

    /** True for the states where the archive bytes no longer exist. */
    val isGone: Boolean get() = this == EXPIRED || this == DELETED || this == SUPERSEDED
}
```

The entity is exactly the one in spec §5 (including `taskId`, `mediaType`, `fileExtension`).

- [ ] **Step 2: Write the enum test (both branches)**

```kotlin
class UserDataExportStateTest {
    @Test
    fun `Given a destroyed state, Then it is gone`() {
        // Given / When / Then
        assertTrue(UserDataExportState.EXPIRED.isGone)
        assertTrue(UserDataExportState.DELETED.isGone)
        assertTrue(UserDataExportState.SUPERSEDED.isGone)
    }

    @Test
    fun `Given a live or failed state, Then it is not gone`() {
        assertFalse(UserDataExportState.PENDING.isGone)
        assertFalse(UserDataExportState.READY.isGone)
        assertFalse(UserDataExportState.FAILED.isGone)
    }
}
```

Import `org.junit.jupiter.api.Assertions.assertTrue` / `assertFalse` (no `kotlin.test` here).

- [ ] **Step 3: Write the repository port**

```kotlin
interface UserDataExportRepositoryInterface {
    fun save(export: UserDataExport): UserDataExport
    fun findById(id: UUID): UserDataExport?
    fun findAllForUser(userId: UUID, cursor: Cursor?, pageSize: Int): Page<UserDataExport>
    fun findPendingForUser(userId: UUID): UserDataExport?
    fun findReadyForUser(userId: UUID): UserDataExport?
    /** The most recent requestedAt across ALL states, DELETED and FAILED included. */
    fun findLastRequestedAtForUser(userId: UUID): Instant?
    fun findExpiredReadyExports(now: Instant): List<UserDataExport>
    fun findAllExportIdsForUser(userId: UUID): List<UUID>
    fun deleteAllForUser(userId: UUID)
}
```

The KDoc on `findLastRequestedAtForUser` is load-bearing: counting only live rows would make
request-cancel-request a free loop.

- [ ] **Step 4: Add the unfiltered membership read (failing test first)**

```kotlin
@Test
fun `Given a pin in a recycled board, Then the export membership read still sees it`() {
    // Given
    val user = createAndSaveUser()
    val board = createAndSaveBoard(user)
    val pin = createAndSavePinInBoard(user, board)
    boardRepository.softDeleteBoard(board)

    // When
    val boards = pinRepository.findBoardsForPinIncludingRecycled(pin.id)

    // Then
    assertEquals(listOf(board.id), boards.map { it.id })
    assertTrue(pinRepository.findPinById(pin.id)!!.boards.isEmpty(), "the API view still filters")
}
```

Implementation: the same query as `getBoardsForPin` **without** `.board.softDeletedAt.isNull`.
`softDeleteBoard` keeps the join rows, so the data is there.

- [ ] **Step 5: Run both modules, then commit**

```bash
git commit -m "feat(domain): export entity, state, repository port and unfiltered board memberships"
```

---

### Task 4: Persistence — model, mapper, repository, migrations

**Files:**
- Create: `api-persistence-sqlite/.../models/UserDataExportModel.kt`, `.../mappers/UserDataExportModelMapper.kt`, `.../repositories/UserDataExportRepository.kt`
- Create: `api-persistence-sqlite/src/test/kotlin/.../UserDataExportRepositoryTest.kt`
- Generate: `dbmigration/1.10.sql`; hand-write `dbmigration/1.11.sql`

- [ ] **Step 1: Write the failing repository test**

```kotlin
class UserDataExportRepositoryTest : RepositoryTest() {
    private val repository = UserDataExportRepository(database)
    private val userRepository = UserRepository(database)

    private fun createAndSaveUser(): User =
        userRepository.saveUser(User(id = UUID.randomUUID(), name = createRandomString()))

    private fun pendingExport(userId: UUID, requestedAt: Instant = Instant.parse("2026-07-22T10:00:00Z")) =
        UserDataExport(
            id = UUID.randomUUID(), userId = userId, state = UserDataExportState.PENDING,
            formatVersion = 1, requestedAt = requestedAt,
        )

    @Test
    fun `Given a pending export, Then it is found for its user`() {
        // Given
        val user = createAndSaveUser()
        val export = repository.save(pendingExport(user.id))
        // When
        val found = repository.findPendingForUser(user.id)
        // Then
        assertEquals(export.id, found?.id)
    }

    @Test
    fun `Given only a ready export, Then no pending export is found`() { ... }

    @Test
    fun `Given a ready export past its expiry, Then it is listed as expired`() { ... }

    @Test
    fun `Given a ready export before its expiry, Then it is not listed as expired`() { ... }

    @Test
    fun `Given a deleted export as the only row, Then it still counts as the last request`() {
        // Given
        val user = createAndSaveUser()
        val at = Instant.parse("2026-07-22T09:00:00Z")
        repository.save(pendingExport(user.id, at).copy(state = UserDataExportState.DELETED))
        // When / Then
        assertEquals(at, repository.findLastRequestedAtForUser(user.id))
    }

    @Test
    fun `Given a second pending export for one user, Then saving it violates the unique index`() {
        // Given
        val user = createAndSaveUser()
        repository.save(pendingExport(user.id))
        // When / Then
        assertThrows(ExportAlreadyInProgressError::class.java) { repository.save(pendingExport(user.id)) }
    }
}
```

Also cover `findAllForUser` ordering + paging, `findReadyForUser`, `findAllExportIdsForUser`,
`deleteAllForUser`.

- [ ] **Step 2: Run and watch it fail**

- [ ] **Step 3: Write the model**

```kotlin
@Suppress("LongParameterList")
@Entity
@Table(name = "user_data_exports")
class UserDataExportModel(
    id: UUID,
    @ManyToOne var user: UserModel,
    var state: String,
    var formatVersion: Int,
    var requestedAt: Instant,
    var taskId: UUID? = null,
    var completedAt: Instant? = null,
    var expiresAt: Instant? = null,
    var storageKey: String? = null,
    var byteSize: Long? = null,
    var sha256: String? = null,
    var mediaType: String? = null,
    var fileExtension: String? = null,
    var failureCode: String? = null,
) : BaseModel(id = id)
```

**The state is a `String`, converted in the mapper** — the repo keeps domain enums out of Ebean
entities (`TaskModel.state`, `ImageDownloadModel.status`). Never an ordinal.

- [ ] **Step 4: Write the mapper and repository**

The mapper maps `userId = model.user.id` and **never** dereferences `model.user.name`: an export row
outlives its owner's tombstone until the cleaner runs, and mapping a soft-deleted `UserModel` is the
known NPE. Verified safe: `.user.id.equalTo(...)` resolves on the local FK column with no join, so
the soft-delete predicate does not filter these queries.

`save` translates the unique-index violation:

```kotlin
// api-domain/.../domain/exports/ExportAlreadyInProgressException.kt
class ExportAlreadyInProgressException : Exception("An export is already in progress for this user")
```

```kotlin
override fun save(export: UserDataExport): UserDataExport =
    try {
        sqlRepository.saveAndReturn(export.toModel(resolveUser(export.userId))).toDomain()
    } catch (error: DuplicateKeyException) {
        throw ExportAlreadyInProgressException()
    }
```

Translating here is mandatory: Konsist bans `io.ebean` from `api-usecases`. **Confirm the exception
type first** — Ebean may wrap SQLite's `SQLITE_CONSTRAINT_UNIQUE` as `DuplicateKeyException` or as a
plain `PersistenceException`; the test in Step 1 tells you which, catch exactly that.

**Correction to the original plan (2026-07-22, during execution).** The adapter cannot throw
`ExportAlreadyInProgressError`: that is a `BaseError` living in `api-usecases`, which
`api-persistence-sqlite` must not depend on. It throws the **domain** exception
`ExportAlreadyInProgressException` instead, exactly as `ImageStore.stage` throws the domain
`ImageTooLargeException` and `SetPinImage` translates it. Task 6's `UserDataExportRequester` catches
the domain exception and rethrows `ExportAlreadyInProgressError` for the presentation layer.

- [ ] **Step 5: Generate `1.10`, hand-write `1.11`**

Run: `./gradlew :api-persistence-sqlite:generateDbMigration`

Then create `dbmigration/1.11.sql` **by hand**, in its own file so a later regeneration cannot drop it:

```sql
create unique index uq_user_data_exports_pending on user_data_exports (user_id) where state = 'PENDING';
```

Verified: SQLite supports partial indexes, and `ebean.migration.run=true` is set in both test
property files, so the index is present in tests.

- [ ] **Step 6: Run, then commit**

```bash
git commit -m "feat(persistence): user_data_exports table, model, mapper and repository"
```

---

### Task 5: The ZIP archive store adapter

**Files:**
- Create: `api-domain/.../domain/exports/ExportArchiveStore.kt` (port, per spec §5)
- Create: `api-storage-filesystem/.../CountingDigestOutputStream.kt`, `.../ZipArchiveSink.kt`, `.../FilesystemZipExportArchiveStore.kt`
- Create: `api-storage-filesystem/src/test/kotlin/.../FilesystemZipExportArchiveStoreTest.kt`
- Modify: `gradle/libs.versions.toml`, `api-storage-filesystem/build.gradle.kts`

- [ ] **Step 1: Fix the dependencies first**

`jackson-databind` is declared **without a version** (it comes from the Quarkus BOM) and
`jackson-datatype-jsr310` is absent. In `libs.versions.toml`:

```toml
jackson-datatype-jsr310 = { module = "com.fasterxml.jackson.datatype:jackson-datatype-jsr310" }
```

In `api-storage-filesystem/build.gradle.kts`:

```kotlin
implementation(platform(libs.quarkus.bom))
implementation(libs.jackson.databind)
implementation(libs.jackson.datatype.jsr310)
```

Run `./gradlew :api-storage-filesystem:dependencies --configuration compileClasspath` and confirm
both resolve with a version before writing code.

- [ ] **Step 2: Write the port** (copy spec §5 verbatim, including `hasFreeSpace`)

- [ ] **Step 3: Write the failing adapter tests**

```kotlin
class FilesystemZipExportArchiveStoreTest {
    @TempDir lateinit var tempDir: Path
    private val store by lazy { FilesystemZipExportArchiveStore(tempDir.toString()) }

    @Test
    fun `Given entries written to the sink, Then the promoted archive contains them`() {
        // Given
        val staged = store.stage { sink ->
            sink.putTextEntry("README.md", "hello")
            sink.putJsonEntry("manifest.json", mapOf("formatVersion" to 1))
            sink.putJsonLinesEntry("pins.jsonl", sequenceOf(mapOf("id" to "a"), mapOf("id" to "b")))
            sink.putBinaryEntry("images/x.bin", ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        }
        // When
        store.promote(staged, "exports/e1.zip")
        // Then
        ZipFile(tempDir.resolve("exports/e1.zip").toFile()).use { zip ->
            assertEquals("hello", zip.getInputStream(zip.getEntry("README.md")).readBytes().decodeToString())
            assertEquals(2, zip.getInputStream(zip.getEntry("pins.jsonl")).readBytes().decodeToString().trim().lines().size)
            assertArrayEquals(byteArrayOf(1, 2, 3), zip.getInputStream(zip.getEntry("images/x.bin")).readBytes())
        }
    }

    @Test
    fun `Given a staged archive, Then its size and digest match the file on disk`() { ... }

    @Test
    fun `Given an entry, Then its digest describes the uncompressed content`() { ... }

    @Test
    fun `Given a failing writer block, Then no temp file is left behind`() {
        assertThrows(IllegalStateException::class.java) { store.stage { error("boom") } }
        assertTrue(Files.list(tempDir.resolve("tmp")).use { it.findAny().isEmpty })
    }

    @Test
    fun `Given a skip offset, Then the stream starts at that byte`() { ... }

    @Test
    fun `Given more entries than the classic ZIP limit, Then the archive is still readable`() {
        val staged = store.stage { sink -> repeat(65_600) { sink.putTextEntry("e/$it.txt", "x") } }
        store.promote(staged, "exports/big.zip")
        ZipFile(tempDir.resolve("exports/big.zip").toFile()).use { assertEquals(65_600, it.size()) }
    }

    @Test
    fun `Given an old export temp file, Then it is discarded as orphaned`() {
        // Given
        val tmp = Files.createDirectories(tempDir.resolve("tmp"))
        val old = Files.createFile(tmp.resolve("export-old.tmp"))
        val recent = Files.createFile(tmp.resolve("export-recent.tmp"))
        val foreign = Files.createFile(tmp.resolve("stage-image.tmp"))
        Files.setLastModifiedTime(old, FileTime.from(Instant.parse("2026-07-01T00:00:00Z")))
        // When
        val removed = store.discardOrphanedStagedFiles(Instant.parse("2026-07-20T00:00:00Z"))
        // Then
        assertEquals(1, removed)
        assertTrue(Files.exists(recent))
        assertTrue(Files.exists(foreign), "an image store temp must never be swept by the export store")
    }
}
```

Note: the ZIP64 case runs in about 150 ms, not seconds (measured).

- [ ] **Step 4: Run and watch them fail**

- [ ] **Step 5: Write `CountingDigestOutputStream`**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.HexFormat

/** Counts and digests bytes on the way through, WITHOUT closing the delegate. */
internal class CountingDigestOutputStream(delegate: OutputStream) : FilterOutputStream(delegate) {
    private val digest = MessageDigest.getInstance("SHA-256")
    var count: Long = 0
        private set

    override fun write(b: Int) {
        out.write(b); digest.update(b.toByte()); count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len); digest.update(b, off, len); count += len
    }

    /** Flushes only: a ZIP entry stream must outlive this wrapper. */
    override fun close() = flush()

    fun digestHex(): String = HEX.formatHex(digest.digest())

    private companion object { private val HEX = HexFormat.of() }
}
```

`FilterOutputStream.write(ByteArray, Int, Int)` defaults to a per-byte loop; overriding it is a
performance requirement for gigabytes of image bytes.

- [ ] **Step 6: Write `ZipArchiveSink`**

```kotlin
internal class ZipArchiveSink(private val zip: ZipOutputStream, private val mapper: ObjectMapper) : ArchiveSink {

    override fun putTextEntry(name: String, text: String): ArchiveEntryDigest =
        entry(name) { out -> out.write(text.toByteArray()) }

    override fun putJsonEntry(name: String, value: Any): ArchiveEntryDigest =
        entry(name) { out -> out.write(mapper.writeValueAsBytes(value)) }

    override fun putJsonLinesEntry(name: String, values: Sequence<Any>): ArchiveEntryDigest =
        entry(name) { out ->
            for (value in values) { out.write(mapper.writeValueAsBytes(value)); out.write('\n'.code) }
        }

    override fun putBinaryEntry(name: String, bytes: InputStream): ArchiveEntryDigest {
        zip.setLevel(Deflater.NO_COMPRESSION)
        try {
            return entry(name) { out -> bytes.use { it.copyTo(out) } }
        } finally {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        }
    }

    private fun entry(name: String, write: (OutputStream) -> Unit): ArchiveEntryDigest {
        zip.putNextEntry(ZipEntry(name))
        val counting = CountingDigestOutputStream(zip)
        write(counting)
        counting.flush()
        zip.closeEntry()
        return ArchiveEntryDigest(name, counting.count, counting.digestHex())
    }
}
```

**Pitfall:** `mapper.writeValue(OutputStream, …)` closes its target (`AUTO_CLOSE_TARGET`), which would
close the whole `ZipOutputStream` after the first JSON entry. `writeValueAsBytes` avoids it entirely;
keep it. **Pitfall:** `setLevel` applies to entries opened after the call, hence the `finally`.

- [ ] **Step 7: Write `FilesystemZipExportArchiveStore`**

```kotlin
class FilesystemZipExportArchiveStore(private val dataDir: String) : ExportArchiveStore {

    override val format = ArchiveFormat(mediaType = "application/zip", fileExtension = "zip")

    private val mapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private val paths = DataDirPaths(dataDir)
    private val tmpDir: Path get() = Path.of(dataDir).resolve("tmp")

    override fun hasFreeSpace(requiredBytes: Long): Boolean {
        Files.createDirectories(tmpDir)
        return Files.getFileStore(tmpDir).usableSpace >= requiredBytes
    }

    @Suppress("TooGenericExceptionCaught")
    override fun stage(block: (ArchiveSink) -> Unit): StagedFile {
        Files.createDirectories(tmpDir)
        val tempPath = Files.createTempFile(tmpDir, TEMP_PREFIX, ".tmp")
        try {
            val fileOut = FileOutputStream(tempPath.toFile())
            val counting = CountingDigestOutputStream(BufferedOutputStream(fileOut))
            try {
                ZipOutputStream(counting).use { zip -> block(ZipArchiveSink(zip, mapper)) }
                fileOut.flush()
                fileOut.channel.force(true)
            } finally {
                fileOut.close()
            }
            return StagedFile(tempPath.toString(), counting.count, counting.digestHex())
        } catch (error: Throwable) {
            Files.deleteIfExists(tempPath)
            throw error
        }
    }

    override fun openStream(storageKey: String, skipBytes: Long): InputStream {
        val channel = Files.newByteChannel(paths.resolveWithinRoot(storageKey))
        channel.position(skipBytes)
        return Channels.newInputStream(channel)
    }

    override fun discardOrphanedStagedFiles(olderThan: Instant): Int {
        if (!Files.isDirectory(tmpDir)) return 0
        return Files.list(tmpDir).use { stream ->
            stream.filter { it.fileName.toString().startsWith(TEMP_PREFIX) }
                .filter { Files.getLastModifiedTime(it).toInstant().isBefore(olderThan) }
                .toList()
        }.count { Files.deleteIfExists(it) }
    }

    // promote / delete / discard: identical to FilesystemImageStore (atomic move via DataDirPaths,
    // deleteIfExists on the resolved key, deleteIfExists on the staged path).

    private companion object { const val TEMP_PREFIX = "export-" }
}
```

Three corrections over the naive version, each with a reason: the file stream is closed in a
`finally` (the counting wrapper deliberately does not close its delegate, so nothing else would);
`force(true)` fsyncs before returning, as `FilesystemImageStore` already does "so a promote never
observes a partially-flushed file"; and the orphan sweep filters on `export-`, so it can never delete
an image store's `stage-*.tmp` even if both stores share a directory.

- [ ] **Step 8: Run `./gradlew :api-storage-filesystem:test`, then commit**

```bash
git commit -m "feat(storage): ZIP-backed export archive store with per-entry digests"
```

---

### Task 6: Errors, error mapper arms, and the requester

Error codes and mapper arms **must land together**: `BaseErrorMapper.statusFor` is an exhaustive
`when` with no `else`, deliberately, so adding an `ErrorCode` without its arm breaks the compilation
of `api-presentation-quarkus` and everything downstream.

**Files:**
- Create: `api-usecases/.../exceptions/UserDataExportError.kt`, `.../tasks/UserDataExportTask.kt`, `.../exports/UserDataExportRequester.kt`
- Modify: `api-usecases/.../exceptions/ErrorCode.kt`, `api-presentation-quarkus/.../mappers/BaseErrorMapper.kt`
- Test: `api-usecases/src/test/kotlin/.../exports/UserDataExportRequesterTest.kt`, `api-presentation-quarkus/src/test/kotlin/.../BaseErrorMapperTest.kt`

- [ ] **Step 1: Add the six codes, the errors, and the six mapper arms in one edit**

```kotlin
// ErrorCode.kt
EXPORT_ALREADY_IN_PROGRESS,
EXPORT_TOO_SOON,
EXPORT_DOES_NOT_EXIST,
EXPORT_INSUFFICIENT_PERMISSIONS,
EXPORT_NOT_READY,
EXPORT_GONE,
```

```kotlin
open class UserDataExportError(message: String, code: ErrorCode) : BaseError(message, code)

// Thrown by the requester when it catches the domain ExportAlreadyInProgressException raised by the
// repository's unique-index guard (see the correction note in Task 4).
class ExportAlreadyInProgressError :
    UserDataExportError("An export is already in progress", ErrorCode.EXPORT_ALREADY_IN_PROGRESS)
class ExportTooSoonError(val retryAfterSeconds: Long) :
    UserDataExportError("Another export was requested too recently", ErrorCode.EXPORT_TOO_SOON)
class ExportDoesNotExistError : UserDataExportError("Export does not exist", ErrorCode.EXPORT_DOES_NOT_EXIST)
class ExportPermissionError :
    UserDataExportError("Export belongs to another user", ErrorCode.EXPORT_INSUFFICIENT_PERMISSIONS)
class ExportNotReadyError : UserDataExportError("Export is not ready", ErrorCode.EXPORT_NOT_READY)
class ExportGoneError : UserDataExportError("Export is no longer available", ErrorCode.EXPORT_GONE)
```

```kotlin
// BaseErrorMapper.statusFor
ErrorCode.EXPORT_ALREADY_IN_PROGRESS -> Response.Status.CONFLICT.statusCode
ErrorCode.EXPORT_TOO_SOON -> Response.Status.TOO_MANY_REQUESTS.statusCode
ErrorCode.EXPORT_DOES_NOT_EXIST -> Response.Status.NOT_FOUND.statusCode
ErrorCode.EXPORT_INSUFFICIENT_PERMISSIONS -> Response.Status.FORBIDDEN.statusCode
ErrorCode.EXPORT_NOT_READY -> Response.Status.CONFLICT.statusCode
ErrorCode.EXPORT_GONE -> Response.Status.GONE.statusCode
```

Verified by `javap` on the resolved jar: `TOO_MANY_REQUESTS` and `GONE` exist in jakarta.ws.rs-api
4.0.0, so no raw-status-code constant and no title fallback are needed. Add one `BaseErrorMapperTest`
case per arm (each arm is a branch under the gate).

- [ ] **Step 2: Write the failing requester tests**

```kotlin
class UserDataExportRequesterTest : BaseTest() {
    private val repository = mockk<UserDataExportRepositoryInterface>()
    private val archiveStore = mockk<ExportArchiveStore>()
    private val enqueueTask = mockk<EnqueueTask>()
    private val reauthenticator = mockk<Reauthenticator>()
    private val clock = mockk<Clock>()
    private val transactionRunner = mockk<TransactionRunner>()
    private val now = Instant.parse("2026-07-22T10:00:00Z")
    private val user = User(id = UUID.randomUUID(), name = "alice")
    private val requester = UserDataExportRequester(
        repository, archiveStore, enqueueTask, reauthenticator, clock, transactionRunner,
        minimumInterval = Duration.ofHours(1),
    )

    @Test
    fun `Given a wrong step-up factor, Then nothing is written and no task is enqueued`() {
        // Given
        every { reauthenticator.reauthenticate(user, "bad") } throws ReauthenticationFailedError()
        // When / Then
        assertThrows(ReauthenticationFailedError::class.java) { requester.request(user, "bad") }
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { enqueueTask.enqueue(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Given no previous export, Then a pending export is created and a task enqueued`() { ... }

    @Test
    fun `Given a pending export, Then requesting again throws ExportAlreadyInProgressError`() { ... }

    @Test
    fun `Given a request within the minimum interval, Then it throws ExportTooSoonError`() { ... }

    @Test
    fun `Given a previous export older than the minimum interval, Then a new export is created`() { ... }

    @Test
    fun `Given a ready export, Then it is superseded and its bytes deleted after the commit`() { ... }

    @Test
    fun `Given a ready export without a storage key, Then no delete is attempted`() { ... }

    @Test
    fun `Given the transaction never runs, Then nothing is written`() { ... }
}
```

The third and fifth cases are the two sides of the `last != null && last.isAfter(...)` short-circuit;
without the fifth, `koverVerify` fails on this package.

**Pitfall:** put the `inTransaction` passthrough stub **inside** each test that reaches the
transaction. The early-throwing tests never call it, and `checkUnnecessaryStub()` would fail them.

- [ ] **Step 3: Run and watch them fail**

- [ ] **Step 4: Implement**

```kotlin
@Suppress("LongParameterList")
class UserDataExportRequester(
    private val repository: UserDataExportRepositoryInterface,
    private val archiveStore: ExportArchiveStore,
    private val enqueueTask: EnqueueTask,
    private val reauthenticator: Reauthenticator,
    private val clock: Clock,
    private val transactionRunner: TransactionRunner,
    private val minimumInterval: Duration,
) {
    fun request(user: User, factor: String): UserDataExport {
        reauthenticator.reauthenticate(user, factor)
        val (export, supersededKey) = transactionRunner.inTransaction { createPending(user) }
        // Outside the transaction on purpose: deleting inside means a later rollback leaves a READY
        // row pointing at bytes that no longer exist, which serves a 500 instead of a clean error.
        supersededKey?.let { archiveStore.delete(it) }
        return export
    }

    private fun createPending(user: User): Pair<UserDataExport, String?> {
        val now = clock.now()
        if (repository.findPendingForUser(user.id) != null) throw ExportAlreadyInProgressError()
        val last = repository.findLastRequestedAtForUser(user.id)
        val earliest = now.minus(minimumInterval)
        if (last != null && last.isAfter(earliest)) {
            throw ExportTooSoonError(Duration.between(earliest, last).seconds.coerceAtLeast(1))
        }
        val ready = repository.findReadyForUser(user.id)
        ready?.let { repository.save(it.copy(state = UserDataExportState.SUPERSEDED, storageKey = null)) }
        val export = repository.save(
            UserDataExport(
                id = UUID.randomUUID(), userId = user.id, state = UserDataExportState.PENDING,
                formatVersion = EXPORT_FORMAT_VERSION, requestedAt = now,
            ),
        )
        val task = enqueueTask.enqueue(
            kind = UserDataExportTask.KIND,
            payload = export.id.toString(),
            maxAttempts = UserDataExportTask.MAX_ATTEMPTS,
        )
        return repository.save(export.copy(taskId = task.id)) to ready?.storageKey
    }

    private companion object { const val EXPORT_FORMAT_VERSION = 1 }
}
```

`coerceAtLeast(1)` keeps `Retry-After: 0` out of the response. The task id is captured, since
`CancelTask.cancel` takes a task id and there is no dedup-key alternative.

- [ ] **Step 5: Run `:api-usecases:test` and `:api-presentation-quarkus:test`, then commit**

```bash
git commit -m "feat(usecases): request a user data export behind step-up re-authentication"
```

---

### Task 7a: The format's content types

**Files:** `api-usecases/.../exports/ExportContent.kt`, `ExportImageExtension.kt`, `ExportReadme.kt` + tests

- [ ] **Step 1: Write the data classes** (exactly the shapes in spec §4)

`ExportedUser`, `ExportedRef`, `ExportedImage`, `ExportedPin`, `ExportedBoard`, `ExportedTag`,
`ExportManifest`, `ExportGenerator`, `ExportExclusion`. Field names are the published contract.

- [ ] **Step 2: Write `ExportImageExtension` test-first (six branches)**

```kotlin
@ParameterizedTest
@CsvSource("image/jpeg,jpg", "image/png,png", "image/webp,webp", "image/gif,gif", "image/avif,avif", "application/x-thing,bin")
fun `Given a mime type, Then the archive extension matches`(mimeType: String, expected: String) {
    assertEquals(expected, ExportImageExtension.forMimeType(mimeType))
}
```

```kotlin
internal object ExportImageExtension {
    fun forMimeType(mimeType: String): String = when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/avif" -> "avif"
        else -> "bin"
    }
}
```

- [ ] **Step 3: Write `ExportReadme`**

A single `fun render(manifest: ExportManifest): String` producing the human-readable text spec §4
requires: contents, per-file meaning, the JSONL convention, how to verify SHA-256, and the explicit
exclusion list (built from `manifest.excluded`, so the two can never disagree). Test that it contains
each file name and each exclusion reason.

- [ ] **Step 4: Write the golden-JSON test**

For every `Exported*` type, serialize a fully-populated instance with the adapter's mapper
configuration and assert the exact JSON string. This is what pins the published format against a
Jackson upgrade or an accidental property rename.

- [ ] **Step 5: Run, then commit**

```bash
git commit -m "feat(usecases): export archive content types, extension map and README"
```

---

### Task 7b: Walking the data into the sink

**Files:** `api-usecases/.../exports/UserDataExportBuilder.kt` (the `stageArchive` half) + tests

**Writing order is load-bearing** (spec §3): `README.md`, `user.json`, `boards.jsonl`, `tags.jsonl`,
**images first**, then `pins.jsonl` referencing only images actually written, then `manifest.json`.

**Lease heartbeat (added 2026-07-23, during execution).** The lease-renewal plumbing is now in place:
`TaskContext` carries a `renewLease: () -> Unit`, `TaskProcessor` fills it, and Task 10's handler
passes `context.renewLease` down. `stageArchive` therefore takes a `renewLease: () -> Unit` parameter
and calls it once per page of pins (both walks) and once per image entry, so an export that outruns
the one-minute lease is never reaped mid-build and never runs concurrently with a second claim.
Test it with a counting lambda: a multi-page walk must renew more than once.

- [ ] **Step 1: Write the failing tests with a recording sink**

```kotlin
private class RecordingSink : ArchiveSink {
    val text = linkedMapOf<String, String>()
    val json = linkedMapOf<String, Any>()
    val jsonLines = linkedMapOf<String, List<Any>>()
    val binary = linkedMapOf<String, ByteArray>()

    override fun putTextEntry(name: String, text: String): ArchiveEntryDigest {
        this.text[name] = text
        return digest(name, text.toByteArray())
    }
    override fun putJsonLinesEntry(name: String, values: Sequence<Any>): ArchiveEntryDigest {
        val list = values.toList()          // the real sink consumes the sequence here too
        jsonLines[name] = list
        return digest(name, list.toString().toByteArray())
    }
    // putJsonEntry / putBinaryEntry likewise
    private fun digest(name: String, bytes: ByteArray) =
        ArchiveEntryDigest(name, bytes.size.toLong(), sha256Hex(bytes))
}
```

The digests must be **real**, not `("", 0)`: with constant digests the manifest test cannot tell a
correct manifest from one that loses or mixes up entries.

Cases:

```kotlin
@Test fun `Given active and recycled pins, Then every pin is written with its deletion marker`()
@Test fun `Given a pin with an image, Then the image entry is written before the pin references it`()
@Test fun `Given an image deleted between the two walks, Then the pin references no image`()
@Test fun `Given a pin in a recycled board, Then the membership is still written`()
@Test fun `Given recycled boards, Then boards jsonl carries them with their deletion marker`()
@Test fun `Given several pages of pins, Then every page is walked`()
@Test fun `Given a completed archive, Then the manifest carries the counts and the entry digests`()
```

- [ ] **Step 2: Run and watch them fail**

- [ ] **Step 3: Implement the walks**

```kotlin
private fun pinSequence(user: User, recycled: Boolean): Sequence<Pin> = sequence {
    var cursor: Cursor? = null
    do {
        val page = if (recycled) {
            pinRepository.findSoftDeletedPinsForUser(user, cursor, pageSize, PinSortStrategy.DELETED_AT_DESC)
        } else {
            pinRepository.findPinsForUser(user, cursor, pageSize, PinSortStrategy.CREATED_AT_DESC)
        }
        yieldAll(page.items)
        cursor = page.nextCursor
    } while (cursor != null)
}
```

Verified: `Page` exposes `items` and `nextCursor`; the sort strategies are `CREATED_AT_ASC`,
`CREATED_AT_DESC`, `DELETED_AT_DESC` (there is no `NEWEST_FIRST`).

The image walk resolves each pin's image with **`imageRepository.findByPinId(pin.id)`**:
`PinModelMapper.toDomain` never populates `Pin.image`, so reading `pin.image` would produce an
archive with zero images and unit tests that pass. It writes `images/<id>.<ext>` and records the path
in a `Set`. The pin walk then emits `image.path` only for paths in that set, and reads memberships
with `findBoardsForPinIncludingRecycled`.

**Counts come from counters incremented inside the sequences** (`onEach { count++ }` read after
`putJsonLinesEntry` returns), never from re-iterating: `sequence {}` is lazy but re-iterable, so a
second pass would silently re-run the whole pagination.

- [ ] **Step 4: Run, then commit**

```bash
git commit -m "feat(usecases): walk user data into the export archive sink"
```

---

### Task 7c: The build state machine

**Files:** `api-usecases/.../exports/UserDataExportBuilder.kt` (the `build` half) + tests

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `Given an export that is not pending, Then the build is a no-op`()
@Test fun `Given a missing user, Then the export is FAILED and a PermanentTaskException is thrown`()
@Test fun `Given insufficient free space, Then the export is FAILED with DISK_FULL and not built`()
@Test fun `Given a successful build, Then the row carries size, digest, media type and extension`()
@Test fun `Given an export cancelled during the build, Then READY is not written and the bytes are deleted`()
@Test fun `Given a failure on the last attempt, Then the export is FAILED and the staged file discarded`()
@Test fun `Given a failure on an earlier attempt, Then the export stays PENDING and the file discarded`()
@Test fun `Given entries being written, Then the task lease is renewed`()
```

- [ ] **Step 2: Run and watch them fail**

- [ ] **Step 3: Implement**

The `build` signature gains `renewLease: () -> Unit` (added 2026-07-23), threaded from the handler
into `stageArchive` so the lease is extended while the archive is written:

```kotlin
fun build(exportId: UUID, isLastAttempt: Boolean, renewLease: () -> Unit) {
    val export = exportRepository.findById(exportId) ?: return
    if (export.state != UserDataExportState.PENDING) return
    val user = userRepository.findUserById(export.userId) ?: run {
        markFailed(export, "USER_GONE"); throw PermanentTaskException("user no longer exists")
    }
    if (!archiveStore.hasFreeSpace(requiredBytes)) {
        markFailed(export, "DISK_FULL"); throw PermanentTaskException("not enough free space")
    }
    val storageKey = storageKeyFor(exportId)
    exportRepository.save(export.copy(storageKey = storageKey))   // referenced BEFORE it exists
    val staged = try {
        stageArchive(export, user, renewLease)
    } catch (error: Throwable) {
        if (isLastAttempt) markFailed(export, "BUILD_FAILED")
        throw error
    }
    archiveStore.promote(staged, storageKey)
    publish(exportId, storageKey, staged)
}

private fun publish(exportId: UUID, storageKey: String, staged: StagedFile) {
    val published = transactionRunner.inTransaction {
        val current = exportRepository.findById(exportId)
        if (current?.state != UserDataExportState.PENDING) {
            false                                   // cancelled, or the account was deleted
        } else {
            exportRepository.save(
                current.copy(
                    state = UserDataExportState.READY,
                    completedAt = clock.now(),
                    expiresAt = clock.now().plus(retention),
                    byteSize = staged.byteSize,
                    sha256 = staged.contentHash,
                    mediaType = archiveStore.format.mediaType,
                    fileExtension = archiveStore.format.fileExtension,
                ),
            )
            true
        }
    }
    if (!published) archiveStore.delete(storageKey)
}
```

The compare-and-set is the difference between "the archive the user asked us to destroy is
downloadable" and correct behaviour. Writing `storageKey` before promoting is what lets the purge and
the account cleaner reclaim bytes from a build that died between the two.

- [ ] **Step 4: Run, then commit**

```bash
git commit -m "feat(usecases): build, publish and fail export archives safely"
```

---

### Task 8: Getter, downloader and deleter

**Files:** `api-usecases/.../exports/{UserDataExportGetter,UserDataExportDownloader,UserDataExportDeleter}.kt` + three test classes

**Interfaces produced:** `get(user, exportId)`, `list(user, cursor, pageSize)`,
`open(user, exportId, skipBytes): OpenedExport` (the non-nullable projection of spec §5),
`delete(user, exportId)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `Given another user's export, Then getting it throws ExportPermissionError`()
@Test fun `Given an unknown id, Then getting it throws ExportDoesNotExistError`()
@Test fun `Given a pending export, Then downloading it throws ExportNotReadyError`()
@Test fun `Given a failed export, Then downloading it throws ExportNotReadyError`()
@Test fun `Given an expired export, Then downloading it throws ExportGoneError`()
@Test fun `Given a ready export missing its storage key, Then downloading throws ExportNotReadyError`()
@Test fun `Given a negative offset, Then downloading throws ExportNotReadyError`()
@Test fun `Given an offset past the end, Then downloading throws ExportNotReadyError`()
@Test fun `Given a ready export, Then the stream is opened eagerly at the requested offset`()
@Test fun `Given a pending export, Then deleting it cancels the task and marks it DELETED`()
@Test fun `Given a pending export with no task id, Then no cancellation is attempted`()
@Test fun `Given a ready export, Then deleting it removes the bytes and marks it DELETED`()
@Test fun `Given an already terminal export, Then deleting it is a no-op`()
```

The "missing storage key" case is what makes the projection's null check a **reachable** branch, which
is why the nullable fields are collapsed here rather than in the controller.

- [ ] **Step 2: Run, watch them fail, implement, run again**

The stream is opened **eagerly** in `open`, not lazily inside a `StreamingOutput`: a purge racing the
download then fails before the status line is sent, instead of truncating a committed `200`.
Do not branch on `CancelTask.cancel`'s `Boolean` return: one side would be unreachable.

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(usecases): read, download and delete user data exports"
```

---

### Task 9: Purge sweep and its own scheduler

**Files:** `api-usecases/.../exports/ReapExpiredUserDataExports.kt`, `api-worker-quarkus/.../ExportsConfig.kt`, `.../ExportRetentionLifecycle.kt` + tests

`ExportsConfig` lives in `api-worker-quarkus`, next to `TaskQueueConfig`: the worker cannot depend on
`api-application`, and every `@ConfigMapping` in this repo lives in its consumer module.

- [ ] **Step 1: Write `ExportsConfig`**

```kotlin
@ConfigMapping(prefix = "exports", namingStrategy = ConfigMapping.NamingStrategy.SNAKE_CASE)
interface ExportsConfig {
    @WithDefault("/var/lib/pinry/exports") fun dataDir(): String
    @WithDefault("P7D") fun retention(): Duration
    @WithDefault("PT1H") fun minimumInterval(): Duration
    @WithDefault("PT1H") fun purgeInterval(): Duration
    @WithDefault("PT6H") fun stagedFileMaxAge(): Duration
    @WithDefault("500") fun pageSize(): Int
}
```

- [ ] **Step 2: Write the failing reaper tests**

```kotlin
@Test fun `Given a ready export past its expiry, Then its bytes are deleted and it becomes EXPIRED`()
@Test fun `Given an expired export without a storage key, Then no delete is attempted`()
@Test fun `Given no expired export, Then nothing is written and zero is returned`()
@Test fun `Given orphaned staged files, Then they are discarded`()
```

- [ ] **Step 3: Implement the reaper, then the lifecycle**

`ExportRetentionLifecycle` mirrors `TaskWorkerLifecycle` (`@Observes StartupEvent`, one immediate
sweep, then `scheduleWithFixedDelay`, each call wrapped in a logging `safeReap()`), but on **its own**
single-thread scheduler produced for it: the task poll scheduler already carries the poll loop and the
lease reaper, and deleting multi-gigabyte archives would block task claiming.

- [ ] **Step 4: Run, then commit**

```bash
git commit -m "feat(worker): purge expired export archives on a dedicated schedule"
```

---

### Task 10: Task handler and CDI wiring

**Files:** `api-worker-quarkus/.../UserDataExportTaskHandler.kt`, `api-application/.../wiring/ExportProducers.kt`, `application.properties` + tests

- [ ] **Step 1: Write the handler test**

```kotlin
@Test
fun `Given the final attempt, Then the builder is told it is the last one`() {
    // Given
    val builder = mockk<UserDataExportBuilder>(relaxed = true)
    val exportId = UUID.randomUUID()
    // When
    UserDataExportTaskHandler(builder).handle(exportId.toString(), TaskContext(attempt = 3, maxAttempts = 3))
    // Then
    verify { builder.build(exportId, isLastAttempt = true, any()) }
}

@Test
fun `Given an earlier attempt, Then the builder is told it is not the last one`() { ... }
```

- [ ] **Step 2: Implement the handler**

```kotlin
@ApplicationScoped
class UserDataExportTaskHandler(private val builder: UserDataExportBuilder) : TaskHandler {
    override val kind = UserDataExportTask.KIND
    override fun handle(payload: String, context: TaskContext) =
        builder.build(
            UUID.fromString(payload),
            isLastAttempt = context.attempt >= context.maxAttempts,
            renewLease = context.renewLease,
        )
}
```

- [ ] **Step 3: Write `ExportProducers` and the properties**

`@Produces` for `ExportArchiveStore` (`FilesystemZipExportArchiveStore(exportsConfig.dataDir())`),
`UserDataExportRequester`, `UserDataExportBuilder`, `ReapExpiredUserDataExports`, injecting config
values and `@ConfigProperty(name = "quarkus.application.version") applicationVersion` for the
manifest's `generator.version`.

**Pitfall (learned in the worker-extraction sub-project):** a produced class must **not** also carry
`@ApplicationScoped` (ambiguous bean), and a discovered bean cannot have `Duration`/`Int`/`String`
constructor parameters. These four are plain classes, produced here, like `PinDownloadTaskHandler`.

Add to `api-application/src/main/resources/application.properties`, next to `images.*` and `tasks.*`:

```properties
exports.data_dir=/var/lib/pinry/exports
exports.retention=P7D
exports.minimum_interval=PT1H
exports.purge_interval=PT1H
exports.staged_file_max_age=PT6H
exports.page_size=500
```

This is a **new volume**: note it for the deployment documentation in Task 14.

- [ ] **Step 4: Run `./gradlew :api-application:test`** (CDI resolves at test boot; an ambiguous or
unsatisfied bean fails here, not at compile time), **then commit**

```bash
git commit -m "feat(worker): account.export task handler and export wiring"
```

---

### Task 11: REST surface

**Files:** `api-presentation-quarkus/.../http/ContentDispositionFileName.kt`, `.../http/RangeHeader.kt`, `.../dtos/output/UserDataExportOutputDto.kt`, `.../mappers/UserDataExportDtoMapper.kt`, `.../controllers/MeExportController.kt` + tests

- [ ] **Step 1: Write `ContentDispositionFileName` test-first**

```kotlin
@Test fun `Given a username with quotes and CRLF, Then the ASCII filename contains neither`()
@Test fun `Given a username with path traversal, Then no slash or dot-dot survives`()
@Test fun `Given a non-ASCII username, Then the ASCII form is sanitized and the UTF-8 form percent-encoded`()
@Test fun `Given a username that sanitizes to nothing, Then the fallback is used`()
@Test fun `Given an over-long username, Then the name is capped`()
```

```kotlin
object ContentDispositionFileName {
    private const val MAX_LENGTH = 100
    private val UNSAFE = Regex("[^A-Za-z0-9._-]+")
    private val ATTR_CHAR = Regex("[A-Za-z0-9!#$&+^_`{}~.-]")

    fun headerValue(rawName: String, fallback: String): String {
        val ascii = UNSAFE.replace(rawName, "-").trim('-').take(MAX_LENGTH).ifEmpty { fallback }
        val encoded = rawName.take(MAX_LENGTH).toByteArray().joinToString("") { byte ->
            val ch = byte.toInt().toChar()
            if (ATTR_CHAR.matches(ch.toString())) ch.toString() else "%%%02X".format(byte)
        }
        return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }
}
```

Percent-encode by hand: `URLEncoder` emits `+` for space and leaves `*` and `'` unescaped, which RFC
5987 forbids. Usernames are only `trim()`ed at registration, so treat them as hostile.

- [ ] **Step 2: Write `RangeHeader` test-first**

```kotlin
@Test fun `Given no header, Then the full body is served`()
@Test fun `Given an open-ended range, Then it runs to the last byte`()
@Test fun `Given a closed range, Then both bounds are honoured`()
@Test fun `Given an end beyond the size, Then it is clamped`()
@Test fun `Given a multi-range header, Then it is ignored and the full body is served`()
@Test fun `Given a suffix range, Then it is ignored and the full body is served`()
@Test fun `Given a start past the end of the file, Then it is unsatisfiable`()
@Test fun `Given a malformed header, Then it is ignored`()
```

`parse(header, totalSize)` returns `ByteRange?` (null = serve everything) and throws
`RangeNotSatisfiableException` for an unsatisfiable start. The suffix form `bytes=-500` is legal HTTP
but deliberately unsupported: pin that choice with the test above, because a naive `split("-")` would
behave by accident.

- [ ] **Step 3: Write the controller**

`download` builds its response entirely from `OpenedExport` (all non-nullable): `Content-Type` from
`mediaType`, `Content-Length` from the slice length (or `totalByteSize`), `ETag` from `sha256`,
`Accept-Ranges: bytes`, `Content-Disposition` from the completion date, the username and
`fileExtension`. The copy into the `StreamingOutput` is **bounded** to the slice length: `copyTo`
would stream to EOF and contradict the announced `Content-Length`. Write that bounded copy as a
private helper with its own test.

`requestExport` parses the header with `ReauthenticationHeader.parsePasswordFactor(reauthHeader)` (as
`MeController.deleteAccount` does) and passes the factor to `requester.request(user, factor)`.

- [ ] **Step 4: Add the `Retry-After` mapper**

`ExportTooSoonError` carries `retryAfterSeconds`; a dedicated `ExceptionMapper<ExportTooSoonError>`
(more specific mappers win) sets the header. Test that it is present and numeric.

- [ ] **Step 5: Run, then commit**

```bash
git commit -m "feat(presentation): user data export endpoints with range-aware download"
```

---

### Task 12: Account deletion erases exports

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `Given a user with an export, Then its rows and derived bytes are erased`() {
    // Given
    val exportId = UUID.randomUUID()
    every { userDataExportRepository.findAllExportIdsForUser(user.id) } returns listOf(exportId)
    // When
    cleaner.deleteAccountData(user.id)
    // Then
    verify { userDataExportRepository.deleteAllForUser(user.id) }
    verify { exportArchiveStore.delete(storageKeyFor(exportId)) }
}

@Test fun `Given a user with no export, Then no archive delete is attempted`()
```

Deleting the **derived** key, not the stored column, is what reclaims an archive promoted by a builder
that died before writing its row.

- [ ] **Step 2: Implement** (ids collected before the transaction, rows deleted inside it before the
user row, bytes deleted after the commit alongside the image bytes), **run, commit**

```bash
git commit -m "fix(usecases): erase export archives when an account is deleted"
```

---

### Task 13: End-to-end integration tests

**Files:** `api-application/src/test/kotlin/.../MeExportIntegrationTest.kt`, `MeExportCompletionIntegrationTest.kt`, `MeExportTestProfile.kt`

- [ ] **Step 1: Write the test profile first**

```kotlin
class MeExportTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "exports.data_dir" to "build/test-export-data/${UUID.randomUUID()}",
        "exports.minimum_interval" to "PT0S",
    )
}
```

Without it the tests write to `/var/lib/pinry/exports` and fail. Mirrors
`MeDeleteCompletionTestProfile`.

- [ ] **Step 2: Endpoint tests**

`POST` with no re-authentication → 403; with a **wrong password** → 403 and no task enqueued; with the
right one → 202. Second `POST` while pending → 409. `GET` list and by id. Another user's export → 403.
Download while pending → 409. `DELETE` → 204.

- [ ] **Step 3: The archive-content test, with a real worker**

Seed a user with two active pins, one recycled pin, an active board, **a recycled board holding a
pin**, a tag and one real image. Request, poll until `READY` (bounded wait, like
`MeDeleteCompletionIntegrationTest`), download, open as a `ZipFile`, and assert: manifest counts and
`formatVersion`; one `pins.jsonl` line per pin with the recycled one carrying `deletedAt`; **the
recycled board present in `boards.jsonl` and still listed in its pin's `boards`**; the image entry at
the path the pin points to with **byte-identical** content; every `entries[].sha256` recomputed.

**Write this test early in the task, not last.** Mocked repositories return shapes the real
persistence never produces: that is exactly how `Pin.image` being always null would slip through.

- [ ] **Step 4: The erasure and header tests**

Account deletion with a ready export: no row, no file. Purge: bytes gone, state `EXPIRED`, row kept.
Headers: persist an export whose `mediaType`/`fileExtension` differ from the adapter's format and
assert the response carries the stored values.

- [ ] **Step 5: Run the full gate, then commit**

Run: `./gradlew check koverVerify`

```bash
git commit -m "test(application): end-to-end user data export coverage"
```

---

### Task 14: OpenAPI, docs, backlog, handoff

- [ ] **Step 1: Regenerate `docs/openapi.json`**

It is produced by the `:api-application` build
(`quarkus.smallrye-openapi.store-schema-directory=../docs`), so `./gradlew :api-application:build`
refreshes it.

- [ ] **Step 2: Document the new volume** in `deploy/` (the exports data dir is a second volume the
current Dockerfile does not create).

- [ ] **Step 3: Refresh `docs/backlog.md`** — replace the portability item with an **import-only**
item pointing at this spec as the format contract, and remove nothing else.

- [ ] **Step 4: Write the handoff** `docs/handoffs/2026-07-22 - handoff - user-data-export.md`,
including the pitfalls learned here: `Pin.image` is never populated by the mapper; promoting
timestamps turns the tombstoned-author NPE into an `UninitializedPropertyAccessException`; the queue
re-claim bug and its three-part fix; and what is **not** validated (real hardware, very large
accounts, ZIP64 beyond the synthetic test, resumed downloads through a real proxy).

- [ ] **Step 5: Commit**

```bash
git commit -m "docs(export): openapi, deploy volume, backlog and handoff"
```

---

## Self-review notes

- **Task order compiles at every step.** Error codes and their mapper arms land together in Task 6;
  `ExportsConfig` exists before the lifecycle that uses it (Task 9); `taskId` exists from Task 3, so
  no later task rewrites an earlier migration.
- **Two pre-existing bugs are fixed first** (Tasks 0a, 0b), each in its own commit, because the export
  is the first consumer that would trip them.
- **Known accepted costs:** N+1 on very large accounts (spec §3), and the second pin walk.
- **Verify while implementing, do not assume:** the exact Ebean exception type for the unique-index
  violation (Task 4 Step 4), and that `generateDbMigration` emits the columns listed here.
