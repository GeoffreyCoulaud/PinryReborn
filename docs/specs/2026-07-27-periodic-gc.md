# Periodic GC and best-effort cleanup parity

Date: 2026-07-27
Status: pending approval
Branch: `feat/periodic-gc`
Depends on: the worker runtime (the `ScheduledExecutorService` producers, the `@ConfigMapping`
convention, the `ExportRetentionLifecycle` pattern), `ImageStore`, `RenditionCache`,
`ExportArchiveStore`, the session-token / pin / image / export / user / task repositories, `Clock`.
No new external dependency. One additive migration (two indexes).

## 1. Goal

Stop the slow accumulation of inert rows and orphaned files the service produces as a side effect of
normal operation. Today five families of residue grow without a sweeper:

- **(a)** expired session tokens (`session_tokens` rows inert once verification rejects them);
- **(b)** orphaned rendition cache subtrees (a failed eviction or a crash mid-write leaves one
  forever);
- **(c1)** soft-deleted account tombstones whose cleaner failed partway (the user stays
  `deleted = true` with child rows and on-disk files);
- **(c2)** orphaned image and export bytes left by an account-delete that committed its DB rows then
  failed on disk: the user is hard-deleted, so this residue is invisible to any row-based sweep and
  needs a filesystem scan;
- **(d)** terminal task rows (`SUCCEEDED` / `DEAD` / `CANCELLED`) that accumulate forever.

The GC runs periodically in the worker (about once per day) and reclaims all five. It is paired with
a uniform best-effort policy on storage cleanup, which reduces the residue at the source and removes
the loop-abort bug in `AccountDeletionCleaner` (where one throwing `imageStore.delete` abandons every
subsequent image and every export archive, and the retry then no-ops because the account is already
gone).

This closes the three P2 GC items in `docs/backlog.md` (session-token GC, cache GC, deleted-account
residue GC), plus the terminal-task accumulation, and hardens storage-cleanup observability.

## 2. Scope

**In scope:**

- **Sequence 1: best-effort cleanup parity.** Every storage cleanup call (image, rendition, export
  archive) becomes best-effort at every call site, logged at WARN through one shared helper. Removes
  the silent bare `runCatching` and the loop-abort in `AccountDeletionCleaner`.
- **Sequence 2: periodic GC.** Four sweeps on a periodic schedule: expired session tokens; orphaned
  rendition subtrees and export archives on disk; tombstoned accounts (re-drive
  `AccountDeletionCleaner`); terminal task rows.
- A dedicated `GcLifecycle` bean reusing the `ExportRetentionLifecycle` pattern, on its own qualified
  single-thread scheduler.
- A `gc.*` config mapping for the interval and the two grace windows.

**Out of scope (deferred or rejected):**

- **No metrics / structured observability surface.** Only WARN logs. The backlog item "surface
  DEAD/failed tasks" stays separate; the GC now sweeps its own DEAD tasks, but handler-failure
  logging is not added here.
- **No pHash / dedup, no audience mechanics, no import, no backfill of `animated`.** Unrelated roadmap
  items.
- **No change to `softDeletedAt` stamping or to the `updatedAt`-on-soft-delete semantic.** Separate
  backlog items.

## 3. Decisions (invariants)

Settled in discussion; the ADR is `docs/adr/0003-periodic-gc-and-best-effort-cleanup.md`.

- **D1: every storage cleanup is best-effort.** `ImageStore.delete`, `ExportArchiveStore.delete` and
  `RenditionCache.evictImage` never propagate an exception to the caller. A business operation that
  already succeeded must not fail because of a file cleanup. The GC is the ultimate guarantor of
  residue. This aligns the two propagating ports on the behaviour `evictImage` already had at all its
  call sites, and removes the `AccountDeletionCleaner` loop-abort.
- **D2: best-effort is logged at WARN through one helper.** The bare `runCatching { }` at eight sites
  is replaced by a shared `StorageCleanup` helper (extensions `deleteQuietly` / `evictImageQuietly`)
  that swallows the failure and emits a WARN with the cause. This is the first logger in
  `api-usecases` main source; it is justified because the synchronous cleanup sites (delete pin,
  recycle bin, image swap) sit behind no worker `safeReap`, so the worker-side logging pattern cannot
  cover them.
- **D3: the GC is periodic on a dedicated scheduler, reusing `ExportRetentionLifecycle`.** No
  `@Scheduled`, no cron. The same shape: an `@ApplicationScoped` bean observing `StartupEvent` /
  `ShutdownEvent`, a `scheduleWithFixedDelay` on a `@Identifier("gc-scheduler")` single-thread
  `ScheduledExecutorService`, and a `safeReap` per sweep. A dedicated thread, for the same reason
  `EXPORT_PURGE_SCHEDULER` is separate from the poll scheduler: heavy filesystem I/O must not block
  task claiming or the lease reaper.
- **D4: the GC is a safety net, complementary to best-effort, not redundant with it.** Best-effort
  reduces residue at the source and fixes the loop-abort; the GC reclaims what no inline path can (a
  crash mid-write, residue from before this change, tombstones whose rows still exist). Each sweep is
  independent and isolated: one throwing sweep is logged and does not stop the others.
- **D5: the orphan disk scan is batched and memory-bounded, not whole-set.** The disk drives the
  iteration (a loan `forEach*OnDisk`), and each batch is checked against the DB (`findMissing*`), so
  memory is bounded by `gc.orphan_batch_size` regardless of disk or DB size. The GC bounds residue,
  not live data, so the scan must not assume a small dataset.
- **D6: the cutoff sweeps use indexes, not full scans.** Orthogonal to D5: the orphan scan's
  `findMissing*` is already a primary-key lookup (indexed by the PK), so D5's batching is a memory
  bound, not a time optimisation. The cutoff sweeps (`deleteExpiredBefore`, `deleteTerminalBefore`)
  filter on columns that accumulate with activity (`session_tokens.expires_at`, `tasks` state and
  `when_modified`), so a migration adds supporting indexes there to keep each sweep a targeted scan
  rather than O(n) over a growing table.

## 4. Sequence 1: best-effort cleanup parity

### 4.1 The helper

A `StorageCleanup` object in `api-usecases` carries the logger and the shared `runQuietly` body;
top-level extension functions on the three ports are the call-site spelling (extension functions are
the one free-function exception the project allows):

```kotlin
object StorageCleanup {
    private val logger = KotlinLogging.logger {}
    internal fun runQuietly(what: String, block: () -> Unit) {
        runCatching(block).onFailure { logger.warn(it) { "storage cleanup failed: $what" } }
    }
}

fun ImageStore.deleteQuietly(storageKey: String) =
    StorageCleanup.runQuietly("image $storageKey") { delete(storageKey) }
fun RenditionCache.evictImageQuietly(imageId: UUID) =
    StorageCleanup.runQuietly("renditions $imageId") { evictImage(imageId) }
fun ExportArchiveStore.deleteQuietly(storageKey: String) =
    StorageCleanup.runQuietly("export $storageKey") { delete(storageKey) }
```

### 4.2 Call sites

Every existing `imageStore.delete`, `exportArchiveStore.delete` and `renditionCache.evictImage` call
switches to its `*Quietly` extension. Concretely: `AccountDeletionCleaner` (the image delete, the
rendition evict and the export delete in its disk loop), `DeletePinImage`, `PinRecycleBin` (both
`permanentlyDelete` and `emptyRecycleBin`), `SetPinImage`, `DownloadPinImage`,
`ReapExpiredUserDataExports`, and `UserDataExportBuilder`. The supersede paths in `SetPinImage` /
`DownloadPinImage` already wrapped in `runCatching`; they switch to the helper too, gaining the WARN.

### 4.3 Acceptance criteria

- A throwing `imageStore.delete` / `exportArchiveStore.delete` no longer propagates from any use
  case: the operation completes and a WARN is logged. Covered by extending the existing use-case unit
  tests with a mock that throws.
- `AccountDeletionCleaner.deleteAccountData` runs its full disk loop even when one image delete
  throws: every image and every export archive is attempted.
- 100% branch coverage on the helper (success and failure paths) and on the touched call sites.

## 5. Sequence 2: periodic GC

Four `Reap*` use cases, each `fun reap(): Int` returning the count reclaimed. Three are logger-free
(the lifecycle `safeAll` logs a sweep-level throw; the count is returned for the eventual metrics
surface). `ReapTombstonedAccounts` is the exception: it re-drives the cleaner per tombstone, and a
re-drive can throw on the DB half (the disk half is best-effort after Sequence 1), so it isolates
each re-drive in its own try/catch and logs the failure itself rather than aborting the batch. Each
sweep is wrapped in its own `safeAll` by the lifecycle, so the sweeps are isolated from each other
too.

### 5.1 Sweeps

- **(a) `ReapExpiredSessionTokens(sessionTokenRepository, clock).reap()`** calls
  `sessionTokenRepository.deleteExpiredBefore(clock.now())`. No grace: an expired token is inert.
- **(b + c2) `ReapOrphanedStorage(renditionCache, exportArchiveStore, imageRepository,
  userDataExportRepository, batchSize).reap()`** reclaims disk residue no row-based sweep can see.
  It is **batched, not whole-set**: the disk drives the iteration and each batch is checked against
  the DB, so memory is bounded by `batchSize` regardless of how many files or rows an active instance
  accumulates (the GC bounds residue, not live data).
  - rendition subtrees: `renditionCache.forEachImageIdOnDisk { ids -> ... }` loans the on-disk ids as
    a lazy sequence; the sweep chunks it by `batchSize`, and for each chunk
    `imageRepository.findMissingImageIds(chunk)` returns the ids with no DB row, each
    `evictImageQuietly`-ed;
  - export archives: `exportArchiveStore.forEachStorageKeyOnDisk { keys -> ... }` loans the on-disk
    storage keys; the sweep parses each key's id, chunks them, and
    `userDataExportRepository.findMissingExportIds(ids)` finds the orphans; the **original storage
    keys** of those orphans are `deleteQuietly`-ed (keys that do not parse to an export id are
    ignored, never deleted);
  - No time bound: an orphan is an orphan.
- **(c1) `ReapTombstonedAccounts(userRepository, accountDeletionCleaner, clock,
  tombstoneGrace).reap()`** queries
  `userRepository.findTombstonedUsersModifiedBefore(clock.now() - tombstoneGrace)` and re-drives
  `accountDeletionCleaner.deleteAccountData(user.id)` on each. The cleaner is idempotent and already
  loads with `findUserByIdIncludingDeleted`, so this finishes a partial delete and hard-deletes the
  tombstone. The grace avoids re-driving an account whose delete task is still in flight. Each
  re-drive is isolated: the cleaner's disk half is best-effort after Sequence 1, but its DB
  transaction can still throw, so a throwing re-drive is logged and the loop continues to the next
  tombstone (one bad tombstone cannot block the rest).
- **(d) `ReapTerminalTasks(taskQueue, clock, terminalTaskGrace).reap()`** calls
  `taskQueue.deleteTerminalBefore(clock.now() - terminalTaskGrace)` over the terminal states
  `SUCCEEDED, DEAD, CANCELLED`. The grace keeps recent history (a DEAD task an operator might
  investigate).

### 5.2 The lifecycle

`GcLifecycle` in `api-worker-quarkus`, the `ExportRetentionLifecycle` shape verbatim:
`@ApplicationScoped`, `@Observes StartupEvent` / `ShutdownEvent`, `start()` runs all four sweeps once
then `scheduleWithFixedDelay({ safeAll() }, interval, interval)`, `stop()` shuts the scheduler down.
`safeAll` runs each sweep through its own
`try { ... } catch (e: Exception) { logger.error(e) { ... } }`, so one sweep's failure does not stop
the rest.

A new producer in `TaskRuntimeProducers`:

```kotlin
internal const val GC_SCHEDULER = "gc-scheduler"

@Produces @ApplicationScoped @Identifier(GC_SCHEDULER)
fun gcScheduler(): ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
```

### 5.3 Wiring

`ReapExpiredSessionTokens` takes only injectable beans: `@ApplicationScoped` directly.
`ReapOrphanedStorage` (takes `batchSize: Int`), `ReapTombstonedAccounts` (`tombstoneGrace: Duration`)
and `ReapTerminalTasks` (`terminalTaskGrace: Duration`) each take a primitive ARC cannot resolve, so
they are produced in a new `GcProducers` in `api-application/wiring`, mirroring
`ExportProducers.reapExpiredUserDataExports`. (`GcConfig` lives in `api-worker-quarkus`, so a use case
in `api-usecases` cannot take it directly; the primitive is read in wiring, exactly as
`ExportsConfig.stagedFileMaxAge` is.)

### 5.4 Acceptance criteria

- Each sweep reclaims exactly its target and nothing else (an expired token, an orphan subtree, a
  tombstone past grace, a terminal task past grace), with repository and use-case tests.
- The lifecycle runs the sweeps on startup and on the fixed delay; a throwing sweep is logged and the
  next sweep still runs (lifecycle / worker unit test of `safeAll`).
- 100% branch coverage per package on every new port method, repository method, use case and the
  lifecycle.

## 6. Domain and ports

New port methods (declared in `api-domain`, no I/O body):

- `RenditionCache.forEachImageIdOnDisk(block: (Sequence<UUID>) -> Unit)` - loan the on-disk cached
  image ids as a lazy sequence.
- `ExportArchiveStore.forEachStorageKeyOnDisk(block: (Sequence<String>) -> Unit)` - same loan shape
  for archive storage keys.

Both are reads whose only implementation today is filesystem traversal, exposed as a **loan** (the
block receives a `Sequence`, not a `Set`) so the sweep never holds the whole disk listing in memory.
The adapter owns the underlying `Files.list` stream and closes it in a `finally` when the block
returns; the sweep chunks the sequence by `batchSize`. Declaring them on the port keeps the domain the
place a sweep asks "what is on disk", and the adapter answers; the sweep use case never touches the
filesystem directly.

## 7. Repository extensions

- `SessionTokenRepositoryInterface.deleteExpiredBefore(now: Instant): Int`.
- `ImageRepositoryInterface.findMissingImageIds(candidates: Collection<UUID>): Set<UUID>` - returns
  the candidates that have no DB row (the orphans); a PK `IN (...)` lookup, bounded by the batch.
- `UserDataExportRepositoryInterface.findMissingExportIds(candidates: Collection<UUID>): Set<UUID>` -
  same shape for export ids.
- `UserRepositoryInterface.findTombstonedUsersModifiedBefore(cutoff: Instant): List<User>` (with
  `setIncludeSoftDeletes`, filtering `deleted = true`).
- `TaskQueueInterface.deleteTerminalBefore(cutoff: Instant): Int` (states
  `SUCCEEDED, DEAD, CANCELLED`, `whenModified < cutoff`).

## 8. Configuration

A `GcConfig` `@ConfigMapping(prefix = "gc", namingStrategy = SNAKE_CASE)` in `api-worker-quarkus`,
mirroring `ExportsConfig`:

| Key | Default | Meaning |
|---|---|---|
| `gc.interval` | `P1D` | Sweep cadence, applied as both initial and fixed delay |
| `gc.tombstone_grace` | `PT24H` | A tombstone younger than this is not re-driven |
| `gc.terminal_task_grace` | `P7D` | A terminal task younger than this is not deleted |
| `gc.orphan_batch_size` | `500` | Chunk size for the orphan disk scan (under SQLite's `IN (...)` variable bound) |

## 9. Testing strategy

TDD, red before green, 100% branch per package. The risk is in the sweeps touching real state, so:

1. **Repository tests (Ebean, `RepositoryTest`):** seed an expired token / a tombstone / a terminal
   task and assert each new method deletes exactly the right rows; seed a live token / an active user
   / a running task and assert it is untouched.
2. **Use-case tests (MockK):** each `Reap*.reap()` returns the count and calls the right port with the
   right argument; `ReapOrphanedStorage` computes the set difference correctly (disk has an extra id,
   DB has an extra id, both cases), processes more ids than `batchSize` across multiple chunks, and
   skips storage keys that do not parse to an export id.
3. **Helper test:** `runQuietly` logs and does not propagate on a throwing block; passes through on a
   succeeding block.
4. **Lifecycle test:** `safeAll` logs and continues when one sweep throws, and runs every sweep on a
   tick.
5. **Best-effort regression:** `AccountDeletionCleaner` and the synchronous delete use cases complete
   when a store throws.

## 10. Risks and accepted trade-offs

- **Startup sweep can be heavy** on a first boot with accumulated residue. Kept because it matches
  `ExportRetentionLifecycle`; the dedicated scheduler isolates it from the poll loop. If it ever
  matters, an initial delay is a one-line config addition.
- **First logger in `api-usecases`.** A deliberate convention widening, justified by the synchronous
  cleanup sites; recorded in the ADR. Subsequent use cases stay logger-free unless they share this
  justification.
- **The orphan scan and the tombstone re-drive overlap on intent but not on input.** A tombstone whose
  child rows are gone cannot be re-driven (the cleaner cannot derive storage keys), and that residue
  is exactly what the orphan scan recovers. They are complementary, kept as separate sweeps.

## 11. Persistence and migration

A new migration adds the indexes the cutoff sweeps rely on (declared on the models, produced by
`generateDbMigration`):

- `session_tokens(expires_at)` - the expired-token sweep deletes `WHERE expires_at < now`.
- `tasks(state, when_modified)` - the terminal-task sweep deletes `WHERE state IN (...) AND
  when_modified < ?`.

`users` is deliberately left unindexed: it grows with the user count (small), and tombstones
(`deleted = true`) are rare, so the tombstone query's scan is cheap. The orphan scan needs no index:
`findMissing*` is a primary-key `IN (...)` lookup.

These indexes are preventive, not the product of a query-plan measurement: a `DELETE ... WHERE col < ?`
on a column that accumulates with activity is O(n) without an index by construction, and there is no
production dataset in alpha to time it against. Consistent with the orphan scan's batching (D5), the
GC takes scaling seriously rather than deferring it to a backlog item.
