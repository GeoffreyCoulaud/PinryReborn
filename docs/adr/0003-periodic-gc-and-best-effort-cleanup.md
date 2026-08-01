# 0003. Periodic GC and uniform best-effort storage cleanup

Status: Accepted
Date: 2026-07-27

## Context

The service accumulates inert state as a side effect of normal operation, and nothing reclaims it:
expired session tokens, orphaned rendition subtrees left by a failed eviction or a crash mid-write,
soft-deleted account tombstones whose cleaner failed, terminal task rows, and orphaned image/export
bytes left by an account-delete that committed its DB rows then failed on disk. Three of these are
open P2 backlog items.

Storage cleanup is also inconsistent. `RenditionCache.evictImage` is best-effort at every call site,
but `ImageStore.delete` and `ExportArchiveStore.delete` propagate exceptions at most call sites, and
all best-effort sites use a bare `runCatching { }` with no logging. Inside `AccountDeletionCleaner`,
the mixed policy is a live bug: one throwing `imageStore.delete` aborts the disk loop, leaving every
subsequent image and every export archive on disk, and because the account is already hard-deleted
the retry no-ops, so the residue is permanent and invisible.

The project is alpha: breaking changes and data loss are acceptable, and the GC keeps the tables and
the disk small, which makes full-scan sweeps and whole-set loads acceptable now.

## Decision

1. **Every storage cleanup is best-effort.** `ImageStore.delete`, `ExportArchiveStore.delete` and
   `RenditionCache.evictImage` never propagate. A business operation that already succeeded does not
   fail because of a file cleanup. The GC is the ultimate guarantor of residue.
2. **Best-effort is logged at WARN through one helper** (`StorageCleanup`), replacing the eight bare
   `runCatching` sites. This is the first logger in `api-usecases` main source: the synchronous
   cleanup sites sit behind no worker `safeReap`, so the worker-side logging pattern cannot cover
   them.
3. **The GC is periodic on a dedicated single-thread scheduler**, reusing the `ExportRetentionLifecycle`
   pattern verbatim (`@Observes StartupEvent` / `ShutdownEvent`, `scheduleWithFixedDelay`,
   `safeReap`). No `@Scheduled`, no cron. A separate thread, like `EXPORT_PURGE_SCHEDULER`, so heavy
   filesystem I/O does not block task claiming.
4. **The GC is a safety net, complementary to best-effort.** It reclaims what no inline path can: a
   crash mid-write, pre-change residue, tombstones. Each sweep is isolated by its own `safeReap`.
5. **The cutoff sweeps use indexes, via one migration.** `deleteExpiredBefore`
   (`session_tokens.expires_at`) and `deleteTerminalBefore` (`tasks` state and `when_modified`) filter
   on columns that accumulate with use; a migration adds the supporting indexes so each sweep is a
   targeted scan, not O(n). The tombstone query on `users` stays a scan (the table is small and
   tombstones rare), and the orphan scan needs no index: `findMissing*` is a primary-key lookup.
6. **The orphan disk scan is batched and memory-bounded.** The disk drives the iteration (a loan
   `forEach*OnDisk`) and each batch is checked against the DB (`findMissing*`), so memory is bounded
   by `gc.orphan_batch_size`. The GC bounds residue, not live data, so the scan does not assume a
   small dataset.

## Consequences

- A throwing store cleanup no longer fails a request and no longer aborts the account-delete loop; it
  is logged at WARN and the GC reclaims the residue later. Operators gain a WARN signal they did not
  have.
- Four new `Reap*` use cases and a `GcLifecycle` bean; five new repository methods; two new port read
  methods (`forEachImageIdOnDisk`, `forEachStorageKeyOnDisk`, loan-pattern, so the orphan scan is
  batched and memory-bounded by `gc.orphan_batch_size`, not by the disk size); a `gc.*` config
  mapping; a `gc-scheduler` producer; one migration adding indexes on `session_tokens.expires_at` and
  `tasks(state, when_modified)`.
- `api-usecases` gains two loggers: `StorageCleanup` (best-effort cleanup) and `ReapTombstonedAccounts`
  (item-level isolation of tombstone re-drives, whose DB half can throw). The "use cases are
  logger-free, the worker logs" convention is widened for these two justified cases; other use cases
  stay logger-free unless they share the justification.
- No coverage perimeter change; the new code is inside and reaches 100% branch per package as usual.
- The orphan scan and the tombstone re-drive overlap on intent but not on input: a tombstone with no
  child rows cannot be re-driven, and that residue is the orphan scan's job. They are kept as
  separate, complementary sweeps.
