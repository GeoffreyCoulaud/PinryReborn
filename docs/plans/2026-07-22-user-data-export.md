# User data export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an authenticated user request, track and download a self-contained archive of all their data, following `docs/specs/2026-07-22-user-data-export.md`.

**Architecture:** Hexagonal. A new `ExportArchiveStore` domain port owns archive production (stage into a temp file, measure size + SHA-256, promote by atomic rename) and **declares** the container format it produces; the ZIP and Jackson mechanics live in the `api-storage-filesystem` adapter, so `api-usecases` gains no serialization dependency. Building is asynchronous on the existing task queue (`account.export`), the archive metadata lives in a new `user_data_exports` table (bytes on disk, never in the database), and the download endpoint reads the media type, size and extension **back from the row** so an archive is always served as what it actually is.

**Tech Stack:** Kotlin, Quarkus 3 (Jakarta REST), Ebean 19 + SQLite, `java.util.zip`, Jackson (adapter only), JUnit 5, MockK, REST Assured, Kover (100% branch coverage).

## Global Constraints

- **100% branch coverage per package**, gated by `koverVerify`. Exercise both sides of every conditional.
- **Strict TDD**: write the failing test first, watch it fail, then the minimal implementation.
- **Clean/Hexagonal**: `api-domain` pure; `api-usecases` depends only on `api-domain`. No `@Transactional` (use the `TransactionRunner` port). **No Jackson, no `java.util.zip` in `api-usecases`** — Konsist (`ArchitectureKonsistTest`) enforces the module DAG and the `api-domain` import allowlist.
- **`api-domain` import allowlist** (Konsist): own packages plus `java.time.Instant`/`Duration`, `java.util.UUID`, `java.io.InputStream`. **Adding any other import to the domain requires editing `ArchitectureKonsistTest` deliberately** — do not widen it casually.
- **Language: English everywhere** — identifiers, prose, commit messages.
- **Conventional commits** (`feat(...)`, `refactor(...)`, `test(...)`, `docs(...)`).
- **No top-level functions** — helpers live in a class/companion/object; extension functions are the only exception.
- **Test naming**: backticked `` `Given ..., Then ...` `` (no "when" in the name); body uses `// Given` / `// When` / `// Then`.
- **Test bases**: integration → `IntegrationTest` (`api-application`, add `@QuarkusTest`); use-case → `BaseTest` (MockK; `checkUnnecessaryStub()` runs in `@AfterEach`, so every `every {}` must be exercised **by the test that declares it**); repository → `RepositoryTest`.
- **Run the gate** with `./gradlew check koverVerify` (needs JDK 25 as the default JVM and libvips for the image tests).

## File Structure

**New files**

- `api-domain/.../domain/storage/StagedFile.kt` — moved from `domain.images` (Task 2).
- `api-domain/.../domain/exports/ExportArchiveStore.kt` — port + `ArchiveSink` + `ArchiveFormat` + `ArchiveEntryDigest`.
- `api-domain/.../domain/entities/UserDataExport.kt` — entity.
- `api-domain/.../domain/enums/UserDataExportState.kt` — `PENDING`, `READY`, `FAILED`, `EXPIRED`, `DELETED`, `SUPERSEDED`.
- `api-domain/.../domain/repositories/UserDataExportRepositoryInterface.kt`.
- `api-persistence-sqlite/.../models/UserDataExportModel.kt`, `.../mappers/UserDataExportModelMapper.kt`, `.../repositories/UserDataExportRepository.kt`.
- `api-persistence-sqlite/src/main/resources/dbmigration/1.10.sql` (+ `model/1.10.model.xml`) — generated, then hand-edited.
- `api-storage-filesystem/.../FilesystemZipExportArchiveStore.kt`, `.../ZipArchiveSink.kt`, `.../CountingDigestOutputStream.kt`.
- `api-usecases/.../usecases/exports/UserDataExportRequester.kt`, `UserDataExportBuilder.kt`, `UserDataExportGetter.kt`, `UserDataExportDownloader.kt`, `UserDataExportDeleter.kt`, `ReapExpiredUserDataExports.kt`, `ExportContent.kt` (the format's data classes).
- `api-usecases/.../usecases/tasks/UserDataExportTask.kt`.
- `api-usecases/.../usecases/exceptions/UserDataExportError.kt`.
- `api-worker-quarkus/.../UserDataExportTaskHandler.kt`, `.../ExportRetentionLifecycle.kt`.
- `api-application/.../ExportsConfig.kt`, `.../ExportProducers.kt`.
- `api-presentation-quarkus/.../controllers/MeExportController.kt`, `.../dtos/output/UserDataExportOutputDto.kt`, `.../mappers/UserDataExportDtoMapper.kt`, `.../http/RangeHeader.kt`, `.../http/ContentDispositionFileName.kt`.

**Modified files**

- `api-domain/.../entities/User.kt`, `Pin.kt`, `Board.kt`, `Tag.kt` — timestamps (Task 1).
- `api-domain/.../images/ImageStore.kt`, `RenditionCache.kt` — `StagedFile` import (Task 2).
- `api-persistence-sqlite/.../mappers/*ModelMapper.kt` — map timestamps.
- `api-usecases/.../usecases/AccountDeletionCleaner.kt` — erase exports (Task 12).
- `api-usecases/.../usecases/exceptions/ErrorCode.kt` — five new codes.
- `api-presentation-quarkus/.../mappers/BaseErrorMapper.kt` — five new arms.
- `api-worker-quarkus/.../TaskRuntimeProducers.kt` or the new lifecycle — schedule the purge.
- `api-application/build.gradle.kts`, `api-storage-filesystem/build.gradle.kts` — Jackson in the adapter.
- `docs/openapi.json` — regenerated (Task 14).

## Task order rationale

Tasks 1 and 2 are refactors with no user-visible behaviour, required by everything downstream. Tasks 3 to 5 build the storage substrate bottom-up. Tasks 6 to 11 build the feature. Tasks 12 and 13 close the two holes that only end-to-end tests can prove (account-deletion residue, real archive content).

---

### Task 1: Promote creation timestamps into the domain

The database already stores `when_created` / `when_modified` on every entity via `BaseModel`, but the domain entities do not carry them, so an export cannot record chronology.

**Decision, apply it consistently:** the new fields are **nullable with a `null` default** (`val createdAt: Instant? = null`). `null` means "this instance was not read from the database" (a use case building a `Pin` before saving it). A non-null default is impossible without a clock, and a clock in the domain would be an I/O dependency; making them non-null would force every construction site — including every existing test — to invent a timestamp. Nullable-with-default keeps this task to a handful of files and every existing call site compiling untouched.

**Files:**
- Modify: `api-domain/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/domain/entities/User.kt`, `Pin.kt`, `Board.kt`, `Tag.kt`
- Modify: `api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/mappers/UserModelMapper.kt`, `PinModelMapper.kt`, `BoardModelMapper.kt`, `TagModelMapper.kt`
- Test: `api-persistence-sqlite/src/test/kotlin/.../repositories/PinRepositoryTest.kt` (add a case), same for board/tag/user repository tests

**Interfaces:**
- Produces: `User.createdAt: Instant?`, `Pin.createdAt: Instant?`, `Pin.updatedAt: Instant?`, `Board.createdAt: Instant?`, `Board.updatedAt: Instant?`, `Tag.createdAt: Instant?`

- [ ] **Step 1: Write the failing repository test**

In `PinRepositoryTest`:

```kotlin
@Test
fun `Given a saved pin, Then reading it back exposes its creation timestamp`() {
    // Given
    val pin = createAndSavePin()

    // When
    val found = pinRepository.findPinById(pin.id)

    // Then
    assertNotNull(found?.createdAt)
    assertNotNull(found?.updatedAt)
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :api-persistence-sqlite:test --tests "*PinRepositoryTest*"`
Expected: FAIL, compilation error `unresolved reference: createdAt`.

- [ ] **Step 3: Add the fields to the entities**

```kotlin
data class Pin(
    override val id: UUID,
    val author: User,
    val sourceContextUrl: String,
    val sourceMediaUrl: String?,
    val description: String,
    val tags: List<Tag>,
    val boards: List<Board>,
    val softDeletedAt: Instant? = null,
    val image: Image? = null,
    /** Set when read from persistence; null on an instance that was never saved. */
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) : Identifiable
```

Same shape for `Board` (`createdAt`, `updatedAt`), `Tag` (`createdAt`) and `User` (`createdAt`).

- [ ] **Step 4: Map them in the model mappers**

In `PinModelMapper.toDomain`, add `createdAt = model.whenCreated, updatedAt = model.whenModified`. Same in the board, tag and user mappers. **Do not** map them in `toModel`: `@WhenCreated` / `@WhenModified` are Ebean-managed and writing them by hand would fight the ORM.

**Pitfall:** `BaseModel.whenCreated` is `lateinit var`. On a model instance that was never persisted, reading it throws `UninitializedPropertyAccessException`. Mappers only ever run on loaded models, but if a test builds a bare `PinModel` and maps it, this throws. Use `model.whenCreated` directly (loaded rows always have it) and do not add a defensive `isInitialized` check: it would be an uncoverable branch under the 100% gate.

- [ ] **Step 5: Run the whole persistence module**

Run: `./gradlew :api-persistence-sqlite:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add api-domain api-persistence-sqlite
git commit -m "feat(domain): carry creation timestamps on user, pin, board and tag"
```

---

### Task 2: Move `StagedFile` to a neutral package

`StagedFile` is about staged bytes on disk, not about images, and the export store is about to share it.

**Files:**
- Move: `api-domain/.../domain/images/ImageStore.kt` (the `StagedFile` declaration at its top) → `api-domain/.../domain/storage/StagedFile.kt`
- Modify: `api-domain/.../domain/images/ImageStore.kt`, `RenditionCache.kt`, `api-storage-filesystem/.../FilesystemImageStore.kt`, `FilesystemRenditionCache.kt`, `api-usecases/.../GetPinImageRendition.kt`, `SetPinImage.kt`, `api-domain/.../images/ImageTransformer.kt`, and every test importing it

**Interfaces:**
- Produces: `fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile(path: String, byteSize: Long, contentHash: String)`

- [ ] **Step 1: Create the new file and delete the old declaration**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.storage

/** Opaque local staging reference plus measured size and content hash. */
data class StagedFile(val path: String, val byteSize: Long, val contentHash: String)
```

- [ ] **Step 2: Fix every import**

Run: `grep -rln "domain.images.StagedFile\|images.StagedFile" --include=*.kt .` and rewrite each import to `...domain.storage.StagedFile`. Files in the same package as the old declaration had no import at all: `grep -rn "StagedFile" api-domain api-usecases` catches those.

- [ ] **Step 3: Compile and run the full gate**

Run: `./gradlew check koverVerify`
Expected: PASS, no behaviour change.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(domain): move StagedFile to domain.storage, shared by images and exports"
```

---

### Task 3: Export entity, state and repository port

**Files:**
- Create: `api-domain/.../domain/enums/UserDataExportState.kt`, `api-domain/.../domain/entities/UserDataExport.kt`, `api-domain/.../domain/repositories/UserDataExportRepositoryInterface.kt`

**Interfaces:**
- Produces: the entity, the enum, and the repository port consumed by Tasks 4, 6, 7, 8, 9, 12.

- [ ] **Step 1: Write the enum and entity**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.enums

enum class UserDataExportState {
    PENDING,
    READY,
    FAILED,
    EXPIRED,
    DELETED,
    SUPERSEDED,
    ;

    /** True for the states where the archive bytes no longer exist. */
    val isGone: Boolean get() = this == EXPIRED || this == DELETED || this == SUPERSEDED
}
```

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import java.time.Instant
import java.util.UUID

data class UserDataExport(
    override val id: UUID,
    val userId: UUID,
    val state: UserDataExportState,
    val formatVersion: Int,
    val requestedAt: Instant,
    val completedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val storageKey: String? = null,
    val byteSize: Long? = null,
    val sha256: String? = null,
    val mediaType: String? = null,
    val fileExtension: String? = null,
    val failureCode: String? = null,
) : Identifiable
```

- [ ] **Step 2: Write the repository port**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import java.time.Instant
import java.util.UUID

interface UserDataExportRepositoryInterface {
    fun save(export: UserDataExport): UserDataExport
    fun findById(id: UUID): UserDataExport?
    /** Newest first. */
    fun findAllForUser(userId: UUID): List<UserDataExport>
    fun findPendingForUser(userId: UUID): UserDataExport?
    fun findReadyForUser(userId: UUID): UserDataExport?
    fun findLastRequestedAtForUser(userId: UUID): Instant?
    fun findExpiredReadyExports(now: Instant): List<UserDataExport>
    fun deleteAllForUser(userId: UUID)
    fun findStorageKeysForUser(userId: UUID): List<String>
}
```

`isGone` is a domain rule with two branches, so it needs its own tiny test to stay inside the coverage gate.

- [ ] **Step 3: Write the enum test**

`api-domain/src/test/kotlin/.../enums/UserDataExportStateTest.kt`:

```kotlin
class UserDataExportStateTest {
    @Test
    fun `Given a terminal destroyed state, Then it is gone`() {
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

- [ ] **Step 4: Run and commit**

Run: `./gradlew :api-domain:test`
Expected: PASS.

```bash
git add api-domain
git commit -m "feat(domain): user data export entity, state and repository port"
```

---

### Task 4: Persistence — model, mapper, repository, migration 1.10

**Files:**
- Create: `api-persistence-sqlite/.../models/UserDataExportModel.kt`, `.../mappers/UserDataExportModelMapper.kt`, `.../repositories/UserDataExportRepository.kt`
- Create: `api-persistence-sqlite/src/test/kotlin/.../repositories/UserDataExportRepositoryTest.kt`
- Generate then edit: `api-persistence-sqlite/src/main/resources/dbmigration/1.10.sql`

**Interfaces:**
- Consumes: `UserDataExport`, `UserDataExportState`, `UserDataExportRepositoryInterface` (Task 3)
- Produces: `UserDataExportRepository` implementing the port, discovered as an `@ApplicationScoped` bean like its siblings

- [ ] **Step 1: Write the failing repository test**

```kotlin
class UserDataExportRepositoryTest : RepositoryTest() {
    private val repository = UserDataExportRepository()

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
    fun `Given only a ready export, Then no pending export is found`() {
        val user = createAndSaveUser()
        repository.save(pendingExport(user.id).copy(state = UserDataExportState.READY))
        assertNull(repository.findPendingForUser(user.id))
    }

    @Test
    fun `Given a ready export past its expiry, Then it is listed as expired`() {
        val user = createAndSaveUser()
        val now = Instant.parse("2026-07-22T10:00:00Z")
        repository.save(
            pendingExport(user.id).copy(
                state = UserDataExportState.READY,
                expiresAt = now.minusSeconds(1),
                storageKey = "exports/x.zip",
            ),
        )
        assertEquals(1, repository.findExpiredReadyExports(now).size)
    }

    @Test
    fun `Given a ready export before its expiry, Then it is not listed as expired`() {
        val user = createAndSaveUser()
        val now = Instant.parse("2026-07-22T10:00:00Z")
        repository.save(
            pendingExport(user.id).copy(
                state = UserDataExportState.READY,
                expiresAt = now.plusSeconds(1),
                storageKey = "exports/x.zip",
            ),
        )
        assertTrue(repository.findExpiredReadyExports(now).isEmpty())
    }
}
```

Add a `pendingExport(userId)` private helper returning a `UserDataExport` with `state = PENDING`, `formatVersion = 1`, `requestedAt = Instant.now()`. Cover `findAllForUser` ordering, `findReadyForUser`, `findLastRequestedAtForUser` (present and absent), `deleteAllForUser` and `findStorageKeysForUser` (a stored key and a null one) in the same class.

- [ ] **Step 2: Run and watch it fail**

Run: `./gradlew :api-persistence-sqlite:test --tests "*UserDataExportRepositoryTest*"`
Expected: FAIL, unresolved reference.

- [ ] **Step 3: Write the Ebean model**

```kotlin
@Entity
@Table(name = "user_data_exports")
class UserDataExportModel(
    id: UUID,
    @ManyToOne var user: UserModel,
    @Enumerated(EnumType.STRING) var state: UserDataExportState,
    var formatVersion: Int,
    var requestedAt: Instant,
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

**Pitfall:** the state is stored as `@Enumerated(EnumType.STRING)`, never ordinal. An ordinal column silently corrupts every row the day someone reorders the enum.

- [ ] **Step 4: Write the mapper and the repository**

Follow `TagModelMapper` / `TagRepository` for shape. The repository is `@ApplicationScoped`, uses generated query beans (`QUserDataExportModel`), and `findPendingForUser` filters `user.id.equalTo(userId).state.equalTo(PENDING)`.

**Pitfall:** the repository must load the `UserModel` to build the model on save. Reuse the pattern in `TagRepository` (`database.find(UserModel::class.java, userId)`), and **never dereference `model.user.name`** in the mapper: an export row can outlive its owner's tombstone until the cleaner runs, and mapping a soft-deleted `UserModel` is exactly the NPE that broke account deletion. Map `userId = model.user.id` only.

- [ ] **Step 5: Generate the migration**

Run: `./gradlew :api-persistence-sqlite:generateDbMigration`
Expected: creates `1.10.sql` and `model/1.10.model.xml` with the new table.

- [ ] **Step 6: Hand-edit the migration to add the partial unique index**

Append to `1.10.sql`:

```sql
create unique index uq_user_data_exports_pending on user_data_exports (user_id) where state = 'PENDING';
```

This is the concurrency guard: two simultaneous requests (a double-click) both pass the in-transaction check otherwise. SQLite supports partial indexes.

- [ ] **Step 7: Run and commit**

Run: `./gradlew :api-persistence-sqlite:test`
Expected: PASS.

```bash
git add api-persistence-sqlite
git commit -m "feat(persistence): user_data_exports table, model, mapper and repository"
```

---

### Task 5: The ZIP archive store adapter

The heart of the format. Everything ZIP-shaped and Jackson-shaped lives here and nowhere else.

**Files:**
- Create: `api-domain/.../domain/exports/ExportArchiveStore.kt`
- Create: `api-storage-filesystem/.../CountingDigestOutputStream.kt`, `.../ZipArchiveSink.kt`, `.../FilesystemZipExportArchiveStore.kt`
- Create: `api-storage-filesystem/src/test/kotlin/.../FilesystemZipExportArchiveStoreTest.kt`
- Modify: `api-storage-filesystem/build.gradle.kts` (add `implementation(libs.jackson.databind)` and the Jackson JSR-310 module)

**Interfaces:**
- Produces:
  - `ArchiveFormat(mediaType: String, fileExtension: String)`
  - `ArchiveEntryDigest(path: String, byteSize: Long, sha256: String)`
  - `ArchiveSink.putTextEntry(name: String, text: String): ArchiveEntryDigest`
  - `ArchiveSink.putJsonEntry(name: String, value: Any): ArchiveEntryDigest`
  - `ArchiveSink.putJsonLinesEntry(name: String, values: Sequence<Any>): ArchiveEntryDigest`
  - `ArchiveSink.putBinaryEntry(name: String, bytes: InputStream): ArchiveEntryDigest`
  - `ExportArchiveStore.format`, `.stage(block)`, `.promote(staged, key)`, `.openStream(key, skipBytes)`, `.delete(key)`, `.discard(staged)`, `.discardOrphanedStagedFiles(olderThan)`

- [ ] **Step 1: Write the port**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import java.io.InputStream
import java.time.Instant

/** What the adapter actually produces, surfaced so no upper layer has to assume it. */
data class ArchiveFormat(val mediaType: String, val fileExtension: String)

data class ArchiveEntryDigest(val path: String, val byteSize: Long, val sha256: String)

interface ArchiveSink {
    fun putTextEntry(name: String, text: String): ArchiveEntryDigest
    fun putJsonEntry(name: String, value: Any): ArchiveEntryDigest
    fun putJsonLinesEntry(name: String, values: Sequence<Any>): ArchiveEntryDigest
    fun putBinaryEntry(name: String, bytes: InputStream): ArchiveEntryDigest
}

interface ExportArchiveStore {
    val format: ArchiveFormat
    fun stage(block: (ArchiveSink) -> Unit): StagedFile
    fun promote(staged: StagedFile, storageKey: String)
    fun openStream(storageKey: String, skipBytes: Long = 0): InputStream
    fun delete(storageKey: String)
    fun discard(staged: StagedFile)
    fun discardOrphanedStagedFiles(olderThan: Instant): Int
}
```

- [ ] **Step 2: Write the failing adapter tests**

```kotlin
class FilesystemZipExportArchiveStoreTest {
    @TempDir
    lateinit var tempDir: Path

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
            assertContentEquals(byteArrayOf(1, 2, 3), zip.getInputStream(zip.getEntry("images/x.bin")).readBytes())
        }
    }

    @Test
    fun `Given a staged archive, Then its size and digest match the file on disk`() {
        val staged = store.stage { it.putTextEntry("a.txt", "content") }
        val bytes = Path.of(staged.path).toFile().readBytes()
        assertEquals(bytes.size.toLong(), staged.byteSize)
        assertEquals(sha256Hex(bytes), staged.contentHash)
    }

    @Test
    fun `Given an entry, Then its digest describes the uncompressed content`() {
        lateinit var digest: ArchiveEntryDigest
        store.stage { digest = it.putTextEntry("a.txt", "content") }
        assertEquals("content".length.toLong(), digest.byteSize)
        assertEquals(sha256Hex("content".toByteArray()), digest.sha256)
    }

    @Test
    fun `Given a failing writer block, Then no temp file is left behind`() {
        assertThrows<IllegalStateException> { store.stage { error("boom") } }
        assertTrue(Files.list(tempDir.resolve("tmp")).use { it.findAny().isEmpty })
    }

    @Test
    fun `Given a skip offset, Then the stream starts at that byte`() {
        val staged = store.stage { it.putTextEntry("a.txt", "content") }
        store.promote(staged, "exports/e2.zip")
        val full = store.openStream("exports/e2.zip").use { it.readBytes() }
        val skipped = store.openStream("exports/e2.zip", 10).use { it.readBytes() }
        assertContentEquals(full.copyOfRange(10, full.size), skipped)
    }

    @Test
    fun `Given more entries than the classic ZIP limit, Then the archive is still readable`() {
        val staged = store.stage { sink ->
            repeat(65_600) { sink.putTextEntry("e/$it.txt", "x") }
        }
        store.promote(staged, "exports/big.zip")
        ZipFile(tempDir.resolve("exports/big.zip").toFile()).use { zip ->
            assertEquals(65_600, zip.size())
        }
    }

    @Test
    fun `Given an old staged temp file, Then it is discarded as orphaned`() { /* touch a file, set its mtime in the past, assert 1 removed and the recent one kept */ }
}
```

- [ ] **Step 3: Run and watch it fail**

Run: `./gradlew :api-storage-filesystem:test`
Expected: FAIL, unresolved reference.

- [ ] **Step 4: Write `CountingDigestOutputStream`**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Counts bytes and digests them on the way through, without closing the delegate: a ZIP entry
 * stream must outlive the per-entry wrapper.
 */
internal class CountingDigestOutputStream(delegate: OutputStream) : FilterOutputStream(delegate) {
    private val digest = MessageDigest.getInstance("SHA-256")
    var count: Long = 0
        private set

    override fun write(b: Int) {
        out.write(b)
        digest.update(b.toByte())
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        digest.update(b, off, len)
        count += len
    }

    /** Does NOT close the delegate. */
    override fun close() = flush()

    fun digestHex(): String = HexFormat.of().formatHex(digest.digest())
}
```

**Pitfall:** `FilterOutputStream.write(ByteArray, Int, Int)` defaults to a per-byte loop through `write(Int)`. Overriding it is a performance requirement here (gigabytes of image bytes), not a nicety.

- [ ] **Step 5: Write `ZipArchiveSink`**

```kotlin
internal class ZipArchiveSink(
    private val zip: ZipOutputStream,
    private val mapper: ObjectMapper,
) : ArchiveSink {

    override fun putTextEntry(name: String, text: String): ArchiveEntryDigest =
        deflatedEntry(name) { out -> out.write(text.toByteArray()) }

    override fun putJsonEntry(name: String, value: Any): ArchiveEntryDigest =
        deflatedEntry(name) { out -> out.write(mapper.writeValueAsBytes(value)) }

    override fun putJsonLinesEntry(name: String, values: Sequence<Any>): ArchiveEntryDigest =
        deflatedEntry(name) { out ->
            for (value in values) {
                out.write(mapper.writeValueAsBytes(value))
                out.write('\n'.code)
            }
        }

    override fun putBinaryEntry(name: String, bytes: InputStream): ArchiveEntryDigest {
        // Already-compressed payloads: level 0 skips deflate work while keeping the streaming
        // data descriptor, so neither CRC nor size is needed up front (unlike a STORED entry,
        // which would force reading every image twice).
        zip.setLevel(Deflater.NO_COMPRESSION)
        val digest = entry(name) { out -> bytes.use { it.copyTo(out) } }
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        return digest
    }

    private fun deflatedEntry(name: String, write: (OutputStream) -> Unit): ArchiveEntryDigest =
        entry(name, write)

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

**Pitfall, the one that silently corrupts archives:** `ObjectMapper.writeValue(OutputStream, value)` closes the target stream by default (`AUTO_CLOSE_TARGET`), which would close the whole `ZipOutputStream` after the first JSON entry. `writeValueAsBytes` sidesteps it entirely; keep it that way, and if a future change needs streaming JSON, disable `JsonGenerator.Feature.AUTO_CLOSE_TARGET` explicitly.

**Pitfall:** `setLevel` applies to entries opened *after* the call, so it must be set before `putNextEntry` and restored after `closeEntry`.

- [ ] **Step 6: Write `FilesystemZipExportArchiveStore`**

Mirror `FilesystemImageStore`: plain class taking `dataDir: String` (**not** `@ApplicationScoped`; ARC cannot satisfy a `String` constructor parameter, and a class must not be both discovered and produced), reuse `DataDirPaths` for containment and atomic move, stage into `<dataDir>/tmp/`.

```kotlin
class FilesystemZipExportArchiveStore(private val dataDir: String) : ExportArchiveStore {

    override val format = ArchiveFormat(mediaType = "application/zip", fileExtension = "zip")

    private val mapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Suppress("TooGenericExceptionCaught")
    override fun stage(block: (ArchiveSink) -> Unit): StagedFile {
        Files.createDirectories(tmpDir)
        val tempPath = Files.createTempFile(tmpDir, "export-", ".tmp")
        try {
            val counting = CountingDigestOutputStream(BufferedOutputStream(Files.newOutputStream(tempPath)))
            ZipOutputStream(counting).use { zip -> block(ZipArchiveSink(zip, mapper)) }
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
    // promote / delete / discard: identical to FilesystemImageStore
}
```

**Pitfall:** seek with a positioned `SeekableByteChannel`, never `InputStream.skip`, which is allowed to skip fewer bytes than asked and would silently serve the wrong range. **Pitfall:** `CountingDigestOutputStream.close()` deliberately does not close its delegate, so here the delegate must be closed explicitly after the `use` block, or the counting stream must wrap the file stream *inside* something that does. Close the underlying file stream in a `finally`, and assert the size on disk equals `staged.byteSize` in the test (Step 2 already does).

- [ ] **Step 7: Run the tests**

Run: `./gradlew :api-storage-filesystem:test`
Expected: PASS. The ZIP64 case takes a few seconds; that is expected.

- [ ] **Step 8: Commit**

```bash
git add api-domain api-storage-filesystem
git commit -m "feat(storage): ZIP-backed export archive store with per-entry digests"
```

---

### Task 6: `UserDataExportRequester` + error codes

**Files:**
- Create: `api-usecases/.../usecases/exports/UserDataExportRequester.kt`, `api-usecases/.../usecases/exceptions/UserDataExportError.kt`, `api-usecases/.../usecases/tasks/UserDataExportTask.kt`
- Modify: `api-usecases/.../usecases/exceptions/ErrorCode.kt`
- Create: `api-usecases/src/test/kotlin/.../exports/UserDataExportRequesterTest.kt`

**Interfaces:**
- Consumes: `UserDataExportRepositoryInterface`, `ExportArchiveStore`, `EnqueueTask`, `Clock`, `TransactionRunner`
- Produces: `UserDataExportRequester.request(user: User): UserDataExport`, `UserDataExportTask.KIND = "account.export"`, `MAX_ATTEMPTS = 3`, and the five new `ErrorCode` entries

- [ ] **Step 1: Add the error codes and errors**

```kotlin
// ErrorCode.kt, appended to the enum
EXPORT_ALREADY_IN_PROGRESS,
EXPORT_TOO_SOON,
EXPORT_DOES_NOT_EXIST,
EXPORT_NOT_READY,
EXPORT_GONE,
```

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

open class UserDataExportError(message: String, code: ErrorCode) : BaseError(message, code)

class ExportAlreadyInProgressError :
    UserDataExportError("An export is already in progress", ErrorCode.EXPORT_ALREADY_IN_PROGRESS)

class ExportTooSoonError(val retryAfterSeconds: Long) :
    UserDataExportError("Another export was requested too recently", ErrorCode.EXPORT_TOO_SOON)

class ExportDoesNotExistError : UserDataExportError("Export does not exist", ErrorCode.EXPORT_DOES_NOT_EXIST)
class ExportNotReadyError : UserDataExportError("Export is not ready", ErrorCode.EXPORT_NOT_READY)
class ExportGoneError : UserDataExportError("Export is no longer available", ErrorCode.EXPORT_GONE)
```

- [ ] **Step 2: Write the failing use-case tests**

```kotlin
class UserDataExportRequesterTest : BaseTest() {
    private val repository = mockk<UserDataExportRepositoryInterface>()
    private val archiveStore = mockk<ExportArchiveStore>()
    private val enqueueTask = mockk<EnqueueTask>()
    private val clock = mockk<Clock>()
    private val transactionRunner = mockk<TransactionRunner>()
    private val now = Instant.parse("2026-07-22T10:00:00Z")
    private val requester = UserDataExportRequester(
        repository, archiveStore, enqueueTask, clock, transactionRunner,
        minimumInterval = Duration.ofHours(1),
    )

    @Test
    fun `Given no previous export, Then a pending export is created and a task enqueued`() { ... }

    @Test
    fun `Given a pending export, Then requesting again throws ExportAlreadyInProgressError`() { ... }

    @Test
    fun `Given a request within the minimum interval, Then it throws ExportTooSoonError`() { ... }

    @Test
    fun `Given a ready export, Then it is superseded and its bytes deleted`() { ... }

    @Test
    fun `Given a ready export without a storage key, Then no delete is attempted`() { ... }

    @Test
    fun `Given the transaction never runs, Then nothing is written`() {
        // Given: inTransaction stubbed as a no-op that never invokes the block
        every { transactionRunner.inTransaction<UserDataExport>(any()) } returns pendingExport()
        // When
        requester.request(user)
        // Then
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { enqueueTask.enqueue(any(), any(), any(), any(), any(), any()) }
    }
}
```

**Pitfall (`checkUnnecessaryStub` is global):** the early-throwing tests never reach the transaction, so the `inTransaction` passthrough stub must live **inside** each test that reaches it, never in a `@BeforeEach`. This bit `PasswordChanger` and `AccountDeleter` before.

- [ ] **Step 3: Run and watch it fail**

Run: `./gradlew :api-usecases:test --tests "*UserDataExportRequesterTest*"`
Expected: FAIL.

- [ ] **Step 4: Implement**

```kotlin
class UserDataExportRequester(
    private val repository: UserDataExportRepositoryInterface,
    private val archiveStore: ExportArchiveStore,
    private val enqueueTask: EnqueueTask,
    private val clock: Clock,
    private val transactionRunner: TransactionRunner,
    private val minimumInterval: Duration,
) {
    fun request(user: User): UserDataExport = transactionRunner.inTransaction {
        val now = clock.now()
        if (repository.findPendingForUser(user.id) != null) throw ExportAlreadyInProgressError()
        val last = repository.findLastRequestedAtForUser(user.id)
        if (last != null && last.isAfter(now.minus(minimumInterval))) {
            throw ExportTooSoonError(Duration.between(now.minus(minimumInterval), last).seconds)
        }
        repository.findReadyForUser(user.id)?.let { ready ->
            ready.storageKey?.let { archiveStore.delete(it) }
            repository.save(ready.copy(state = UserDataExportState.SUPERSEDED, storageKey = null))
        }
        val export = repository.save(
            UserDataExport(
                id = UUID.randomUUID(),
                userId = user.id,
                state = UserDataExportState.PENDING,
                formatVersion = EXPORT_FORMAT_VERSION,
                requestedAt = now,
            ),
        )
        enqueueTask.enqueue(
            kind = UserDataExportTask.KIND,
            payload = export.id.toString(),
            maxAttempts = UserDataExportTask.MAX_ATTEMPTS,
        )
        export
    }

    private companion object {
        const val EXPORT_FORMAT_VERSION = 1
    }
}
```

**Note:** the bytes are deleted inside the transaction here, which is the one place this plan accepts a non-transactional side effect inside a transaction: the alternative (deferring to after commit) leaves the superseded archive on disk if the process dies, and the purge sweep would not catch it because its row is no longer `READY`. A failed delete throws and rolls the request back, which is the safe direction.

- [ ] **Step 5: Run, then commit**

Run: `./gradlew :api-usecases:test --tests "*UserDataExportRequesterTest*"`
Expected: PASS.

```bash
git add api-usecases
git commit -m "feat(usecases): request a user data export"
```

---

### Task 7: `UserDataExportBuilder` — the archive content

**Files:**
- Create: `api-usecases/.../usecases/exports/ExportContent.kt` (the format's data classes), `.../exports/UserDataExportBuilder.kt`
- Create: `api-usecases/src/test/kotlin/.../exports/UserDataExportBuilderTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 3 to 6, plus `PinRepositoryInterface.findPinsForUser` / `findSoftDeletedPinsForUser`, `BoardRepositoryInterface.findAllBoardsForUser`, `TagRepositoryInterface.findAllTagsForUser`, `ImageStore.openStream`
- Produces: `UserDataExportBuilder.build(exportId: UUID, isLastAttempt: Boolean)`

**Format writing order (this differs from a naive reading of spec §8 and is not negotiable):** a ZIP holds **one open entry at a time**, so image bytes cannot be written while `pins.jsonl` is open. The builder therefore walks the pins **twice**: pass one writes `pins.jsonl`, pass two writes the image entries. Both passes are paginated, so memory stays constant; the cost is a second read of the pin pages. Collecting the image keys in memory during pass one was rejected: it is unbounded in the number of pins, which is the thing this design refuses to be.

- [ ] **Step 1: Write the content data classes**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

/** The archive's version-1 wire format. Field names are the contract; changing one is a format break. */
data class ExportedUser(val id: String, val name: String, val createdAt: Instant?)

data class ExportedRef(val id: String, val name: String)

data class ExportedImage(
    val id: String,
    val path: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val animated: Boolean,
    val byteSize: Long,
    val sha256: String,
    val createdAt: Instant,
)

data class ExportedPin(
    val id: String,
    val description: String,
    val sourceContextUrl: String,
    val sourceMediaUrl: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val deletedAt: Instant?,
    val tags: List<ExportedRef>,
    val boards: List<ExportedRef>,
    val image: ExportedImage?,
)

data class ExportedBoard(
    val id: String,
    val name: String,
    val description: String,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val deletedAt: Instant?,
)

data class ExportedTag(val id: String, val name: String, val createdAt: Instant?)

data class ExportManifest(
    val formatVersion: Int,
    val generator: ExportGenerator,
    val exportId: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val user: ExportedUser,
    val counts: Map<String, Int>,
    val entries: List<ArchiveEntryDigest>,
    val excluded: List<ExportExclusion>,
)

data class ExportGenerator(val name: String, val version: String)
data class ExportExclusion(val what: String, val why: String)
```

- [ ] **Step 2: Write the failing builder tests**

Use a **fake sink** that records entries, so the test asserts content without touching a real ZIP:

```kotlin
private class RecordingSink : ArchiveSink {
    val text = mutableMapOf<String, String>()
    val json = mutableMapOf<String, Any>()
    val jsonLines = mutableMapOf<String, List<Any>>()
    val binary = mutableListOf<String>()
    override fun putTextEntry(name: String, text: String): ArchiveEntryDigest { ... }
    override fun putJsonLinesEntry(name: String, values: Sequence<Any>): ArchiveEntryDigest {
        jsonLines[name] = values.toList()   // the sequence MUST be consumed here, as the real sink does
        return ArchiveEntryDigest(name, 0, "")
    }
    // ...
}
```

Cases:

```kotlin
@Test fun `Given a user with pins, Then every pin including the recycle bin is written`()
@Test fun `Given a pin with an image, Then its bytes are written under images and referenced by path`()
@Test fun `Given a pin without an image, Then its image field is null and no binary entry is written`()
@Test fun `Given several pages of pins, Then every page is walked`()
@Test fun `Given a completed archive, Then the manifest carries the counts and the entry digests`()
@Test fun `Given a successful build, Then the export becomes READY with size, digest, media type and extension`()
@Test fun `Given an export that is not pending, Then the build is a no-op`()
@Test fun `Given a missing or tombstoned user, Then the export is FAILED and a PermanentTaskException is thrown`()
@Test fun `Given a failure on the last attempt, Then the export is FAILED and the staged file discarded`()
@Test fun `Given a failure on a non-final attempt, Then the export stays PENDING and the staged file is discarded`()
```

- [ ] **Step 3: Run and watch it fail**

Run: `./gradlew :api-usecases:test --tests "*UserDataExportBuilderTest*"`
Expected: FAIL.

- [ ] **Step 4: Implement the builder**

```kotlin
class UserDataExportBuilder(
    private val exportRepository: UserDataExportRepositoryInterface,
    private val userRepository: UserRepositoryInterface,
    private val pinRepository: PinRepositoryInterface,
    private val boardRepository: BoardRepositoryInterface,
    private val tagRepository: TagRepositoryInterface,
    private val imageStore: ImageStore,
    private val archiveStore: ExportArchiveStore,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
    private val retention: Duration,
    private val pageSize: Int,
    private val generatorVersion: String,
) {
    @Suppress("TooGenericExceptionCaught")
    fun build(exportId: UUID, isLastAttempt: Boolean) {
        val export = exportRepository.findById(exportId) ?: return
        if (export.state != UserDataExportState.PENDING) return
        val user = userRepository.findUserById(export.userId)
            ?: run {
                markFailed(export, "USER_GONE")
                throw PermanentTaskException("user no longer exists")
            }
        val staged = try {
            stageArchive(export, user)
        } catch (error: Throwable) {
            if (isLastAttempt) markFailed(export, "BUILD_FAILED")
            throw error
        }
        promoteAndPublish(export, user, staged)
    }
}
```

`stageArchive` writes, in order: `README.md`, `user.json`, `pins.jsonl` (active pages then recycle-bin pages), `boards.jsonl`, `tags.jsonl`, then the image entries (second walk), then `manifest.json` last, built from the digests collected so far.

**Pitfall:** `putJsonLinesEntry` takes a `Sequence`; build the pages with `generateSequence` over the cursor so pages are pulled lazily inside the sink, never materialized:

```kotlin
private fun pinSequence(user: User, softDeleted: Boolean): Sequence<Pin> = sequence {
    var cursor: Cursor? = null
    do {
        val page = if (softDeleted) {
            pinRepository.findSoftDeletedPinsForUser(user, cursor, pageSize, PinSortStrategy.NEWEST_FIRST)
        } else {
            pinRepository.findPinsForUser(user, cursor, pageSize, PinSortStrategy.NEWEST_FIRST)
        }
        yieldAll(page.items)
        cursor = page.nextCursor
    } while (cursor != null)
}
```

Check `Page`'s actual property names (`api-domain/.../entities/Page.kt`) before writing this; the plan assumes `items` and `nextCursor`.

**Pitfall:** promoting and publishing must happen in this order — `promote` first, then the transactional row update. A row that says `READY` before the file is in place is a 500 waiting to happen.

- [ ] **Step 5: Run, then commit**

```bash
git add api-usecases
git commit -m "feat(usecases): build the user data export archive"
```

---

### Task 8: Getter, downloader and deleter use cases

**Files:**
- Create: `api-usecases/.../usecases/exports/UserDataExportGetter.kt`, `UserDataExportDownloader.kt`, `UserDataExportDeleter.kt`
- Create: the three matching test classes

**Interfaces:**
- Produces:
  - `UserDataExportGetter.get(user: User, exportId: UUID): UserDataExport`, `.list(user: User): List<UserDataExport>`
  - `UserDataExportDownloader.open(user: User, exportId: UUID, skipBytes: Long): OpenedExport` where `data class OpenedExport(val export: UserDataExport, val stream: InputStream)`
  - `UserDataExportDeleter.delete(user: User, exportId: UUID)`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `Given another user's export, Then getting it throws ExportPermissionError`()
@Test fun `Given an unknown id, Then getting it throws ExportDoesNotExistError`()
@Test fun `Given a pending export, Then downloading it throws ExportNotReadyError`()
@Test fun `Given a failed export, Then downloading it throws ExportNotReadyError`()
@Test fun `Given an expired export, Then downloading it throws ExportGoneError`()
@Test fun `Given a ready export, Then downloading opens a stream at the requested offset`()
@Test fun `Given a pending export, Then deleting it cancels the task and marks it DELETED`()
@Test fun `Given a ready export, Then deleting it removes the bytes and marks it DELETED`()
@Test fun `Given an already terminal export, Then deleting it is a no-op`()
```

Ownership uses a `403`-mapped error; reuse the existing convention (`ImagePermissionError` maps to `IMAGE_INSUFFICIENT_PERMISSIONS` → 403). Add `EXPORT_INSUFFICIENT_PERMISSIONS` to `ErrorCode` and an `ExportPermissionError`, mapped to `FORBIDDEN` in Task 11.

- [ ] **Step 2: Implement, run, commit**

Cancelling a `PENDING` export reuses `CancelTask`. It needs the task id, which the export row does not carry — **either** add a `task_id` column in Task 4's model (preferred: one nullable UUID column, set at enqueue time) **or** have `CancelTask` accept a dedup key. Take the column: it also makes operator debugging possible. If Task 4 is already committed, add the column in this task with its own migration `1.11`.

```bash
git add api-usecases
git commit -m "feat(usecases): read, download and delete user data exports"
```

---

### Task 9: Purge sweep + worker scheduling

**Files:**
- Create: `api-usecases/.../usecases/exports/ReapExpiredUserDataExports.kt`, its test
- Create: `api-worker-quarkus/.../ExportRetentionLifecycle.kt`, its test

**Interfaces:**
- Produces: `ReapExpiredUserDataExports.reap(): Int`

- [ ] **Step 1: Write the failing use-case tests**

```kotlin
@Test fun `Given a ready export past its expiry, Then its bytes are deleted and it becomes EXPIRED`()
@Test fun `Given an expired export without a storage key, Then no delete is attempted`()
@Test fun `Given no expired export, Then nothing is written and zero is returned`()
@Test fun `Given orphaned staged files, Then they are discarded`()
```

- [ ] **Step 2: Implement**

```kotlin
class ReapExpiredUserDataExports(
    private val repository: UserDataExportRepositoryInterface,
    private val archiveStore: ExportArchiveStore,
    private val clock: Clock,
    private val stagedFileMaxAge: Duration,
) {
    fun reap(): Int {
        val now = clock.now()
        val expired = repository.findExpiredReadyExports(now)
        for (export in expired) {
            export.storageKey?.let { archiveStore.delete(it) }
            repository.save(export.copy(state = UserDataExportState.EXPIRED, storageKey = null))
        }
        archiveStore.discardOrphanedStagedFiles(now.minus(stagedFileMaxAge))
        return expired.size
    }
}
```

- [ ] **Step 3: Write `ExportRetentionLifecycle`**

Mirror `TaskWorkerLifecycle` exactly: `@Observes StartupEvent` → one immediate `reap()` plus `scheduleWithFixedDelay` at the configured purge interval, each call wrapped in a `safeReap()` that logs and swallows. Reuse the `@Identifier(TASK_POLL_SCHEDULER)` scheduler rather than creating a second thread.

**Pitfall:** `TASK_POLL_SCHEDULER` is `internal` in `TaskWorkerLifecycle.kt` — same module, so it is reachable; do not duplicate the literal.

- [ ] **Step 4: Run, then commit**

```bash
git add api-usecases api-worker-quarkus
git commit -m "feat(worker): purge expired export archives on a schedule"
```

---

### Task 10: Task handler and CDI wiring

**Files:**
- Create: `api-worker-quarkus/.../UserDataExportTaskHandler.kt`, its test
- Create: `api-application/.../ExportsConfig.kt`, `.../ExportProducers.kt`

**Interfaces:**
- Consumes: `UserDataExportBuilder`, `ExportArchiveStore`
- Produces: CDI beans for `UserDataExportRequester`, `UserDataExportBuilder`, `ReapExpiredUserDataExports`, `ExportArchiveStore`

- [ ] **Step 1: Write the handler test**

```kotlin
@Test
fun `Given a task payload, Then the builder is invoked with the export id and the attempt flag`() {
    // Given
    val builder = mockk<UserDataExportBuilder>(relaxed = true)
    val handler = UserDataExportTaskHandler(builder)
    val exportId = UUID.randomUUID()

    // When
    handler.handle(exportId.toString(), TaskContext(attempt = 3, maxAttempts = 3))

    // Then
    verify { builder.build(exportId, isLastAttempt = true) }
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
        builder.build(UUID.fromString(payload), isLastAttempt = context.attempt >= context.maxAttempts)
}
```

The comparison mirrors `TaskProcessor.settle`, which marks a task `DEAD` on exactly `attempts >= maxAttempts`.

- [ ] **Step 3: Write the config and producers**

```kotlin
@ConfigMapping(prefix = "exports", namingStrategy = ConfigMapping.NamingStrategy.SNAKE_CASE)
interface ExportsConfig {
    @WithDefault("/var/lib/pinry/exports")
    fun dataDir(): String

    @WithDefault("P7D")
    fun retention(): Duration

    @WithDefault("PT1H")
    fun minimumInterval(): Duration

    @WithDefault("PT1H")
    fun purgeInterval(): Duration

    @WithDefault("PT6H")
    fun stagedFileMaxAge(): Duration

    @WithDefault("500")
    fun pageSize(): Int
}
```

`ExportProducers` (`api-application`) `@Produces` each use case, injecting the repositories and the config values. **Pitfall, learned the hard way in the worker-extraction sub-project:** a produced class must **not** also carry `@ApplicationScoped`, or the bean is ambiguous; and a discovered bean cannot have `Duration`/`Int`/`String` constructor parameters. So `UserDataExportRequester`, `UserDataExportBuilder`, `ReapExpiredUserDataExports` and `FilesystemZipExportArchiveStore` are plain classes, produced here, exactly like `PinDownloadTaskHandler`.

- [ ] **Step 4: Run the application module, then commit**

Run: `./gradlew :api-application:test`
Expected: PASS (CDI resolves at test boot; an ambiguous or unsatisfied bean fails here, not at compile time).

```bash
git add api-worker-quarkus api-application
git commit -m "feat(worker): account.export task handler and export wiring"
```

---

### Task 11: REST surface

**Files:**
- Create: `api-presentation-quarkus/.../controllers/MeExportController.kt`, `.../dtos/output/UserDataExportOutputDto.kt`, `.../mappers/UserDataExportDtoMapper.kt`, `.../http/RangeHeader.kt`, `.../http/ContentDispositionFileName.kt`
- Modify: `api-presentation-quarkus/.../mappers/BaseErrorMapper.kt`
- Create: the matching tests, including `BaseErrorMapperTest` arms

- [ ] **Step 1: Write `ContentDispositionFileName` and its test first**

```kotlin
object ContentDispositionFileName {
    private val SAFE = Regex("[^A-Za-z0-9._-]+")

    /** `attachment; filename="..."; filename*=UTF-8''...` with a sanitized ASCII fallback. */
    fun headerValue(rawName: String, fallback: String): String { ... }
}
```

Tests, with hostile input, because usernames are only trimmed at registration:

```kotlin
@Test fun `Given a username with quotes and CRLF, Then the ASCII filename contains neither`()
@Test fun `Given a username with path traversal, Then no slash or dot-dot survives`()
@Test fun `Given a non-ASCII username, Then the ASCII name is sanitized and the UTF-8 form is percent-encoded`()
@Test fun `Given a username that sanitizes to nothing, Then the fallback is used`()
```

- [ ] **Step 2: Write `RangeHeader` and its test**

```kotlin
internal data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
}

internal object RangeHeader {
    /** Null when the header is absent or must be ignored (multi-range); throws on unsatisfiable. */
    fun parse(header: String?, totalSize: Long): ByteRange?
}
```

```kotlin
@Test fun `Given no header, Then the full body is served`()
@Test fun `Given an open-ended range, Then it runs to the last byte`()
@Test fun `Given a closed range, Then both bounds are honoured`()
@Test fun `Given an end beyond the size, Then it is clamped`()
@Test fun `Given a multi-range header, Then it is ignored and the full body is served`()
@Test fun `Given a start past the end of the file, Then it is unsatisfiable`()
@Test fun `Given a malformed header, Then it is ignored`()
```

- [ ] **Step 3: Write the controller**

```kotlin
@Path("/api/v1/me/exports")
@Authenticated
class MeExportController(
    private val securityIdentity: SecurityIdentity,
    private val requester: UserDataExportRequester,
    private val getter: UserDataExportGetter,
    private val downloader: UserDataExportDownloader,
    private val deleter: UserDataExportDeleter,
) {
    @POST
    @APIResponse(responseCode = "202", description = "Export accepted")
    fun requestExport(@HeaderParam(ReauthenticationHeader.HEADER) reauthHeader: String?): RestResponse<UserDataExportOutputDto> { ... }

    @GET fun list(): List<UserDataExportOutputDto>

    @GET @Path("/{id}") fun get(id: UUID): UserDataExportOutputDto

    @GET @Path("/{id}/download")
    fun download(id: UUID, @HeaderParam("Range") range: String?): RestResponse<StreamingOutput> { ... }

    @DELETE @Path("/{id}") fun delete(id: UUID): RestResponse<Void>
}
```

`download` serves `Content-Type` from `export.mediaType`, `Content-Length` from the slice length (or `export.byteSize` with no range), `ETag` from `export.sha256`, `Accept-Ranges: bytes`, and the `Content-Disposition` built from the completion date, the username and `export.fileExtension`. Follow `ImageController.serveOriginal` for the `StreamingOutput` shape.

**Pitfall:** the copy into the output must be **bounded** to the range length. `copyTo` streams to EOF and would contradict the announced `Content-Length`. Write the bounded copy as a small private helper with its own test.

- [ ] **Step 4: Add the error mapper arms**

```kotlin
ErrorCode.EXPORT_ALREADY_IN_PROGRESS -> Response.Status.CONFLICT.statusCode
ErrorCode.EXPORT_TOO_SOON -> TOO_MANY_REQUESTS_STATUS_CODE
ErrorCode.EXPORT_DOES_NOT_EXIST -> Response.Status.NOT_FOUND.statusCode
ErrorCode.EXPORT_NOT_READY -> Response.Status.CONFLICT.statusCode
ErrorCode.EXPORT_GONE -> Response.Status.GONE.statusCode
ErrorCode.EXPORT_INSUFFICIENT_PERMISSIONS -> Response.Status.FORBIDDEN.statusCode
```

**Verify before writing:** `BaseErrorMapper` falls back to the literal title `"Unprocessable Entity"` for **any** status with no `Response.Status` constant. Check in a scratch test that `Response.Status.fromStatusCode(429)` and `(410)` are non-null on this Jakarta REST version. `GONE` certainly exists; `TOO_MANY_REQUESTS` was added in JAX-RS 2.1 but **confirm it**, because if it is absent, a `429` would be titled "Unprocessable Entity", and the fallback needs a proper title map instead of a single constant.

Every new arm needs its own `BaseErrorMapperTest` case: each arm is a branch under the 100% gate.

- [ ] **Step 5: `Retry-After` on 429**

`ExportTooSoonError` carries `retryAfterSeconds`. `BaseErrorMapper` maps `BaseError` generically, so add a dedicated `ExceptionMapper<ExportTooSoonError>` (more specific mappers win in Jakarta REST) that sets the header, or set it in the controller by catching and rethrowing. Prefer the dedicated mapper; test that the header is present and numeric.

- [ ] **Step 6: Run, then commit**

```bash
git add api-presentation-quarkus
git commit -m "feat(presentation): user data export endpoints with range-aware download"
```

---

### Task 12: Account deletion erases exports

Without this, deleting an account leaves a complete copy of its data on disk for up to seven days.

**Files:**
- Modify: `api-usecases/.../usecases/AccountDeletionCleaner.kt`
- Modify: `api-usecases/src/test/kotlin/.../AccountDeletionCleanerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `Given a user with an export, Then its rows and bytes are erased`() {
    // Given
    every { userDataExportRepository.findStorageKeysForUser(user.id) } returns listOf("exports/e1.zip")
    // When
    cleaner.deleteAccountData(user.id)
    // Then
    verify { userDataExportRepository.deleteAllForUser(user.id) }
    verify { exportArchiveStore.delete("exports/e1.zip") }
}

@Test
fun `Given a user with no export, Then no archive delete is attempted`() { ... }
```

- [ ] **Step 2: Implement**

Collect the keys **before** the transaction (like the image keys), delete the rows **inside** it before the user row, and delete the bytes **after** the commit, next to the image bytes.

- [ ] **Step 3: Run, then commit**

```bash
git add api-usecases
git commit -m "fix(usecases): erase export archives when an account is deleted"
```

---

### Task 13: End-to-end integration tests

The three tests that carry the real risk. The account-deletion sub-project proved that mocked unit tests and code review both miss what a real worker run catches.

**Files:**
- Create: `api-application/src/test/kotlin/.../MeExportIntegrationTest.kt`, `MeExportCompletionIntegrationTest.kt`

- [ ] **Step 1: Endpoint-level integration tests**

`POST` without re-authentication → 403; with it → 202. Second `POST` while pending → 409. `GET` list and by id. Another user's export → 403. Download while pending → 409. `DELETE` → 204.

- [ ] **Step 2: The archive-content test, with a real worker**

Seed a user with two active pins, one recycle-bin pin, a board, a tag and one **real** image; request the export; poll `GET /api/v1/me/exports/{id}` until `READY` (bounded wait, like `MeDeleteCompletionIntegrationTest`); download the bytes; open them as a `ZipFile`; assert:

- `manifest.json` counts match what was seeded, and `formatVersion` is 1
- `pins.jsonl` has one line per pin, recycle-bin pin included with a non-null `deletedAt`
- the image entry exists at the `path` the pin points to, and its bytes are **byte-identical** to what was uploaded
- every `entries[].sha256` matches a recomputed digest of that entry

- [ ] **Step 3: The two erasure tests**

Account deletion with a ready export: no row, and **no file on disk**. Purge: force `expiresAt` into the past, run the reaper, assert bytes gone, state `EXPIRED`, row present.

- [ ] **Step 4: The headers-come-from-the-row test**

Persist an export whose `mediaType` / `fileExtension` differ from the adapter's current format; assert the response carries the stored values. This is the test that fails the day someone hardcodes `application/zip` in the controller.

- [ ] **Step 5: Run the full gate, then commit**

Run: `./gradlew check koverVerify`
Expected: PASS, including 100% branch coverage per package.

```bash
git add api-application
git commit -m "test(application): end-to-end user data export coverage"
```

---

### Task 14: OpenAPI and documentation

- [ ] **Step 1: Regenerate `docs/openapi.json`**

Follow the repository's existing regeneration step (the same one used by the profile-management sub-project).

- [ ] **Step 2: Refresh the backlog**

In `docs/backlog.md`, replace the "User data export / import (portability)" item with an **import-only** item, referencing this spec as the format contract. The export half is recorded by its handoff, git history and tag, not by the backlog.

- [ ] **Step 3: Write the handoff**

`docs/handoffs/2026-07-22 - handoff - user-data-export.md`: what shipped, the pitfalls learned, and what is **not** validated (real hardware, very large accounts, ZIP64 beyond the synthetic test, resumed downloads through a real proxy).

- [ ] **Step 4: Commit**

```bash
git add docs
git commit -m "docs(export): openapi, backlog and handoff for user data export"
```

---

## Self-review notes

- **Spec coverage:** §4 format → Tasks 5 and 7; §5 domain → Tasks 2, 3, 5; §6 use cases → Tasks 6 to 9; §7 REST → Task 11; §8 worker → Tasks 7 and 10; §9 retention → Tasks 6, 9, 10; §10 account deletion → Task 12; §11 errors → Tasks 6, 8, 11; §12 persistence → Task 4; §13 testing → distributed, closed by Task 13.
- **Two deviations from the spec, both deliberate and both explained in place:** the builder walks pins **twice** because a ZIP holds one open entry at a time (Task 7), and the export row needs a `task_id` column so a pending export can be cancelled (Task 8).
- **Two things to verify while implementing rather than assume:** `Page`'s property names before writing `pinSequence` (Task 7), and whether `Response.Status.fromStatusCode(429)` is non-null on this Jakarta REST version (Task 11).
