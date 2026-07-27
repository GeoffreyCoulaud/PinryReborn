# Plan: periodic GC and best-effort cleanup parity

Date: 2026-07-27
Spec: `docs/specs/2026-07-27-periodic-gc.md`
ADR: `docs/adr/0003-periodic-gc-and-best-effort-cleanup.md`
Branch: `feat/periodic-gc`

## Conventions for every task

- **TDD, red first.** Each behavioural task commits the failing test alone as
  `test(scope): <behaviour>` before any implementation. The task reviewer sees only the red commit as
  red evidence.
- **Scope.** Touch only the files a task lists. Adjacent defects go to the backlog, not this branch.
- **Coverage.** New code is inside the gate perimeter; 100% branch per package. The
  `models`/`models.bases` and Ebean `Q*`/`@io.ebean.typequery.Generated` exclusions still hold.
- **Gate.** `./gradlew check koverVerify` runs once at Verify, not per task; each task's own tests
  must pass before the next task begins.

## Ordering and dependencies

Sequence 1 (best-effort parity) lands first (T1, T2): it is the foundation, and T6 reuses the
`*Quietly` extensions.

Sequence 2 (GC): T3, T4, T5, T7, T8, T9 are mutually independent. T6 depends on T1, T4, T5. T10
depends on T3, T6, T7, T8. The default dispatch is serial, branch-in-place, in the order below;
worktree parallelisation of the independent tasks is an operator option, not a default.

New `Reap*` use cases live in: `ReapExpiredSessionTokens`, `ReapOrphanedStorage` and
`ReapTombstonedAccounts` at the `api-usecases` root (`usecases/`, alongside `AccountDeleter`);
`ReapTerminalTasks` under `usecases/tasks/` (alongside `ReapExpiredTasks`).

---

## Sequence 1: best-effort cleanup parity

### T1: StorageCleanup helper and best-effort extensions

The single helper every storage cleanup call site will use.

**Depends on:** none.
**Files:**
- `api-usecases/src/main/kotlin/.../usecases/StorageCleanup.kt` (new): `object StorageCleanup` with
  `internal fun runQuietly(what: String, block: () -> Unit)` (`runCatching(block).onFailure { logger.warn(it) { ... } }`),
  plus top-level extensions `ImageStore.deleteQuietly(storageKey)`,
  `RenditionCache.evictImageQuietly(imageId)`, `ExportArchiveStore.deleteQuietly(storageKey)`. Extension
  functions are the project's one free-function exception; the logger is the first in `api-usecases`.
**Tests (red first):**
- `api-usecases/src/test/kotlin/.../usecases/StorageCleanupTest.kt` (new): `runQuietly` executes the
  block, logs WARN and does **not** propagate when the block throws; passes through when it succeeds.
  The three extensions are one-line wrappers and are covered transitively by T2 (failure paths) and
  T6 (success and failure); they are not re-tested here to avoid interaction-only tests.
**Implementation:** as above; the WARN message includes `what` (e.g. `"image <key>"`) and the cause.
**Acceptance:** the three extensions compile against the ports; `runQuietly` is 100% branch-covered
(success and failure); a throwing block never escapes `runQuietly`.

### T2: Switch every storage cleanup call site to `*Quietly`

Make every side-effect storage cleanup best-effort; remove the `AccountDeletionCleaner` loop-abort
and the error-masking in the rollback / no-op-swap / post-commit-supersede paths. (Amended after the
T2 task review: the original "already wrapped in `runCatching`" note was true only of the supersede
blocks, not the rollback paths, and the plan had missed `UserDataExportRequester`.)

**Depends on:** T1.
**Files:**
- `api-usecases/.../AccountDeletionCleaner.kt`: the three disk-loop calls become
  `imageStore.deleteQuietly`, `renditionCache.evictImageQuietly`, `exportArchiveStore.deleteQuietly`.
- `api-usecases/.../DeletePinImage.kt`, `PinRecycleBin.kt` (`permanentlyDelete` and
  `emptyRecycleBin`), `exports/ReapExpiredUserDataExports.kt`, `exports/UserDataExportBuilder.kt`:
  switch each cleanup call to its `*Quietly` extension.
- `api-usecases/.../SetPinImage.kt`, `DownloadPinImage.kt`: switch ALL `imageStore.delete` /
  `renditionCache.evictImage` calls, not only the supersede `runCatching` blocks. The rollback delete
  in the failure handler (`SetPinImage` `catch { ...; imageStore.delete(storageKey); throw e }`,
  `DownloadPinImage` `catch { ...; imageStore.delete(...); failRetryable(...) }`) and the no-op-swap
  delete currently let a cleanup failure escape into the caller's error path, masking the original
  cause. Each becomes `imageStore.deleteQuietly` so the original error is preserved and the orphan is
  left for T6.
- `api-usecases/.../exports/UserDataExportRequester.kt`: the post-commit supersede delete of the
  previous READY archive (`supersededKey?.let { archiveStore.delete(it) }`) becomes
  `archiveStore.deleteQuietly`; the transaction has committed, so this is post-success cleanup.
- NOT touched: `UserDataExportDeleter` (`archiveStore.delete(export.storageKey)`): that delete IS
  the user's primary `DELETE /me/exports/{id}` operation, not a side-effect cleanup, so a disk
  failure is a real failure the caller must see. Add an inline comment documenting this exception to
  D1 so a future edit does not silently make it best-effort.
**Tests (red first):**
- `AccountDeletionCleanerTest`: `Given imageStore.delete throws mid-loop, Then the disk loop still
  attempts every image and every export archive` (red today). Fixture: at least two pins with images
  and at least one export archive.
- Extend `DeletePinImageTest`, `PinRecycleBinTest`: `Given the image store throws, Then the delete /
  recycle still succeeds`.
- `SetPinImageTest`: `Given the rollback delete throws, Then the original promote/save error is
  preserved` (red today: the delete exception masks the original).
- `DownloadPinImageTest`: `Given the rollback delete throws, Then the task fails with the original
  cause` and `Given the no-op-swap delete throws, Then the task still succeeds` (not retried).
- `UserDataExportRequesterTest`: `Given the superseded archive delete throws, Then the request still
  succeeds` (red today: 500 for a committed request).
**Acceptance:** no side-effect storage cleanup propagates from a use case; `AccountDeletionCleaner`
runs its full disk loop under a mid-loop throw; rollback and no-op-swap deletes preserve the original
error; the post-commit supersede delete does not fail a committed request; `UserDataExportDeleter`
still propagates (primary user operation, documented); pre-existing tests stay green.

---

## Sequence 2: periodic GC

### T3: Sweep expired session tokens

Reclaim expired `session_tokens` rows.

**Depends on:** none.
**Files:**
- `api-domain/.../repositories/SessionTokenRepositoryInterface.kt`: add
  `fun deleteExpiredBefore(now: Instant): Int`.
- `api-persistence-sqlite/.../repositories/SessionTokenRepository.kt`: impl
  `QSessionTokenModel().expiresAt.lessThan(now).delete()`.
- `api-usecases/.../ReapExpiredSessionTokens.kt` (new): `@ApplicationScoped`;
  `fun reap(): Int = sessionTokenRepository.deleteExpiredBefore(clock.now())`.
**Tests (red first):**
- `SessionTokenRepositoryTest` (extends `RepositoryTest`): seeded expired + valid tokens:
  `deleteExpiredBefore(now)` deletes only the expired, returns the count.
- `ReapExpiredSessionTokensTest` (MockK): `reap()` calls `deleteExpiredBefore(clock.now())` and
  returns its count.
**Acceptance:** expired tokens deleted, valid tokens untouched, count returned.

### T4: Loan-pattern disk enumeration ports

Let a sweep ask "what is on disk" without holding the whole listing in memory.

**Depends on:** none.
**Files:**
- `api-domain/.../images/RenditionCache.kt`: add
  `fun forEachImageIdOnDisk(block: (Sequence<UUID>) -> Unit)`.
- `api-domain/.../exports/ExportArchiveStore.kt`: add
  `fun forEachStorageKeyOnDisk(block: (Sequence<String>) -> Unit)`.
- `api-storage-filesystem/.../FilesystemRenditionCache.kt`: impl: `Files.list(cacheRoot).use { stream
  -> block(stream.asSequence().mapNotNull { parseImageId(it) }) }`.
- `api-storage-filesystem/.../FilesystemZipExportArchiveStore.kt`: impl: list `<dataDir>/exports/`
  (NOT the dataDir root, which also holds `tmp/` staged files), and yield `exports/<fileName>` for
  each regular file.
**Tests (red first):**
- `FilesystemRenditionCacheTest`: `forEachImageIdOnDisk` yields the ids of subtrees present and
  closes the stream (a follow-up call succeeds; no open handle leaks).
- `FilesystemZipExportArchiveStoreTest`: `forEachStorageKeyOnDisk` yields the storage keys present.
**Acceptance:** both ports enumerate disk lazily and close the `Files.list` stream when the block
returns; 100% branch.

### T5: Batched "which candidates are missing" repository queries

Tell the orphan sweep, per batch, which disk ids have no DB row.

**Depends on:** none.
**Files:**
- `api-domain/.../repositories/ImageRepositoryInterface.kt`: add
  `fun findMissingImageIds(candidates: Collection<UUID>): Set<UUID>`.
- `api-domain/.../repositories/UserDataExportRepositoryInterface.kt`: add
  `fun findMissingExportIds(candidates: Collection<UUID>): Set<UUID>`.
- `api-persistence-sqlite/.../repositories/ImageRepository.kt`,
  `UserDataExportRepository.kt`: impl: fetch the ids that exist (`id isIn candidates`), return
  `candidates - existing`.
**Tests (red first):**
- `ImageRepositoryTest`, `UserDataExportRepositoryTest` (extend `RepositoryTest`): seeded rows:
  `findMissing*` returns exactly the candidates with no row; an empty candidate set returns empty.
**Acceptance:** missing ids returned, present ids excluded; the query is a PK `IN (...)` lookup
bounded by the batch.

### T6: Reap orphaned storage (batched)

Reclaim rendition subtrees and export archives whose id is not in the DB, batched by `batchSize`.

**Depends on:** T1 (`*Quietly`), T4 (disk ports), T5 (`findMissing*` repos).
**Files:**
- `api-usecases/.../ReapOrphanedStorage.kt` (new): constructor
  `(renditionCache, exportArchiveStore, imageRepository, userDataExportRepository, batchSize: Int)`;
  `fun reap(): Int` drives each disk loan, chunks by `batchSize`, queries `findMissing*` per chunk,
  and evicts/deletes orphans via the `*Quietly` extensions. The export-id parser handles a storage key
  of the form `exports/<uuid>.<ext>`: strip the `exports/` prefix and the extension, then parse the
  remainder as a `UUID`; a key that fails either step (bad extension or bad UUID) is skipped, never
  deleted. (Since T4 lists only `<dataDir>/exports/`, a wrong-prefix key is unreachable in practice.)
**Tests (red first):**
- `ReapOrphanedStorageTest` (MockK): disk has an extra image id (evicted) and an extra export key
  (deleted); DB has an extra id (untouched); more ids than `batchSize` are processed across multiple
  chunks; an unparseable storage key is skipped, not deleted.
**Acceptance:** orphans on disk reclaimed, live entries untouched, memory bounded by `batchSize`,
unparseable keys ignored.

### T7: Reap tombstoned accounts (re-drive the cleaner)

Finish a partial account delete and hard-delete the tombstone.

**Depends on:** none (`AccountDeletionCleaner` exists).
**Files:**
- `api-domain/.../repositories/UserRepositoryInterface.kt`: add
  `fun findTombstonedUsersModifiedBefore(cutoff: Instant): List<User>`.
- `api-persistence-sqlite/.../repositories/UserRepository.kt`: impl with `setIncludeSoftDeletes`,
  `deleted.isTrue`, `whenModified.lessThan(cutoff)`.
- `api-usecases/.../ReapTombstonedAccounts.kt` (new): constructor
  `(userRepository, accountDeletionCleaner, clock, tombstoneGrace: Duration)`; `fun reap(): Int`
  queries tombstones older than the grace and re-drives
  `accountDeletionCleaner.deleteAccountData(user.id)` on each. **Each re-drive is isolated in its own
  try/catch and logged**: the cleaner's DB transaction can still throw (its disk half is best-effort
  after Sequence 1), so a throwing re-drive is logged and the loop continues to the next tombstone.
  This is the second logger in `api-usecases`.
**Tests (red first):**
- `UserRepositoryTest`: seeded tombstones: `findTombstonedUsersModifiedBefore(cutoff)` returns only
  tombstones older than the cutoff; active users and fresh tombstones excluded. Seed the age with
  `database.sqlUpdate` to back-date `when_modified`: it is `@WhenModified`, which Ebean overwrites on
  every save, so a plain save cannot produce an "old" row.
- `ReapTombstonedAccountsTest` (MockK): `reap()` re-drives the cleaner exactly on the tombstones the
  repo returns; and `Given one re-drive throws, Then the others are still re-driven` (red today: the
  throw aborts the loop).
**Acceptance:** stale tombstones re-driven (and hard-deleted by the cleaner), fresh tombstones and
active users untouched; one throwing re-drive does not stop the rest.

### T8: Reap terminal task rows

Delete `SUCCEEDED` / `DEAD` / `CANCELLED` tasks older than the grace.

**Depends on:** none.
**Files:**
- `api-domain/.../repositories/TaskQueueInterface.kt`: add
  `fun deleteTerminalBefore(cutoff: Instant): Int`.
- `api-persistence-sqlite/.../repositories/EbeanTaskQueue.kt`: impl:
  `state.isIn(TaskState.SUCCEEDED.name, TaskState.DEAD.name, TaskState.CANCELLED.name)` (the column
  is a `String`; follow the existing `.name` convention), `whenModified.lessThan(cutoff)`, delete.
- `api-usecases/.../tasks/ReapTerminalTasks.kt` (new): constructor
  `(taskQueue, clock, terminalTaskGrace: Duration)`;
  `fun reap(): Int = taskQueue.deleteTerminalBefore(clock.now() - terminalTaskGrace)`.
**Tests (red first):**
- `EbeanTaskQueueTest` (extends `RepositoryTest`): seeded terminal + non-terminal tasks:
  `deleteTerminalBefore(cutoff)` deletes only the terminal ones older than the cutoff.
- `ReapTerminalTasksTest` (MockK): `reap()` calls `deleteTerminalBefore(clock.now() - grace)`.
**Acceptance:** terminal tasks past grace deleted; `PENDING`/`RUNNING` and fresh terminals
untouched.

### T9: Migration: indexes on the sweep cutoff columns

Keep the cutoff sweeps targeted, not O(n).

**Depends on:** none (independent of the sweep code).
**Files:**
- `api-persistence-sqlite/.../models/SessionTokenModel.kt`: single-column index on `expiresAt`.
- `api-persistence-sqlite/.../models/TaskModel.kt`: composite index on `(state, when_modified)`.
- Run `./gradlew :api-persistence-sqlite:generateDbMigration` to emit the next
  `dbmigration/<v>.sql` and `model/<v>.model.xml`.
**Notes:** consult the Ebean docs (Context7) for the exact annotation: `@Index` for the single
column, `@Table(indexes = [Index(columnList = "state,when_modified")])` (or the project's existing
pattern) for the composite. Confirm against the generated SQL.
**Tests (red first):**
- A test asserting the generated migration SQL (`dbmigration/<v>.sql`) contains an index on
  `session_tokens (expires_at)` and a composite index on `tasks (state, when_modified)`:
  form-independent (it does not depend on which Ebean annotation produced it), matching the
  `DbMigrationModelCoverageTest` style, so a future model edit cannot silently drop them.
**Acceptance:** the migration applies cleanly at startup (no checksum conflict); both indexes exist
on a fresh DB; the gate is green.

### T10: GarbageCollectionConfig, scheduler, lifecycle and wiring

Orchestrate the four sweeps on a dedicated periodic scheduler.

**Depends on:** T3, T6, T7, T8 (the four `Reap*` use cases).
**Files:**
- `api-worker-quarkus/.../GarbageCollectionConfig.kt` (new): `@ConfigMapping(prefix = "garbage-collection",
  namingStrategy = SNAKE_CASE)` with `interval` (`P1D`), `tombstone_grace` (`PT24H`),
  `terminal_task_grace` (`P7D`), `orphan_batch_size` (`500`).
- `api-worker-quarkus/.../GarbageCollectionLifecycle.kt` (new): `@ApplicationScoped`; declares
  `internal const val GC_SCHEDULER = "gc-scheduler"` (the consumer declares the qualifier constant,
  as `TaskWorkerLifecycle` and `ExportRetentionLifecycle` do); `@Observes StartupEvent`/`ShutdownEvent`;
  `start()` runs all four sweeps once then
  `scheduleWithFixedDelay({ safeAll() }, interval, interval)`; `safeAll()` runs each sweep in its own
  `try { reap() } catch (e: Exception) { logger.error(e) { ... } }`. Mirror `ExportRetentionLifecycle`.
- `api-worker-quarkus/.../TaskRuntimeProducers.kt`: add a
  `@Produces @ApplicationScoped @Identifier(GC_SCHEDULER) fun gcScheduler()` returning
  `Executors.newSingleThreadScheduledExecutor()`, referencing the constant from `GarbageCollectionLifecycle`.
- `api-application/.../wiring/GarbageCollectionProducers.kt` (new): `@Produces @ApplicationScoped` for
  `ReapOrphanedStorage` (`config.orphanBatchSize()`), `ReapTombstonedAccounts`
  (`config.tombstoneGrace()`), `ReapTerminalTasks` (`config.terminalTaskGrace()`), mirroring
  `ExportProducers`.
**Tests (red first):**
- `GarbageCollectionLifecycleTest` (MockK, in `api-worker-quarkus`, following `ExportRetentionLifecycleTest`):
  `start()` runs the sweeps once and schedules `safeAll` on the executor. `safeAll()` is covered for
  100% branch: one tick where every sweep succeeds (all four try arms) and one tick where every sweep
  throws (all four catch arms), asserting each throw is logged and the others still run.
**Acceptance:** the four sweeps run on startup and on the fixed delay; a throwing sweep is logged and
does not stop the others; the bean wires with no unresolved dependency.

---

## After T10

Verify runs the full gate (`./gradlew check koverVerify`) and a holistic review over the branch diff.
Wrap updates `docs/backlog.md` (close the three GC P2 items), writes the handoff, and integrates via a
rebased PR. Improve records any rule the gate should have caught.
