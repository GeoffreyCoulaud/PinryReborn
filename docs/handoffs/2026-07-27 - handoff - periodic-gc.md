# Handoff: periodic GC and best-effort cleanup parity

Branch: `feat/periodic-gc` (cut from `main` on 2026-07-27).
Tier: Plan (ten tasks, two sequences). Spec: `docs/specs/2026-07-27-periodic-gc.md`,
ADR: `docs/adr/0003-periodic-gc-and-best-effort-cleanup.md`, plan:
`docs/plans/2026-07-27-periodic-gc.md`.

## Current state

The periodic GC and the uniform best-effort cleanup parity are implemented end to end across tasks
T1 to T10. The full gate (`./gradlew check koverVerify`) is green, 100% branch coverage per package
holds, and the three P2 GC backlog items (session-token GC, cache GC, deleted-account residue GC) are
closed. The branch is ready to integrate through a rebased PR.

A worker-side `GcLifecycle` bean runs four sweeps on a dedicated single-thread scheduler at roughly
once per day (default `gc.interval=P1D`): expired session tokens, orphaned rendition/export disk
residue, tombstoned accounts, and terminal task rows. Storage cleanup is uniformly best-effort and
logged at WARN through one shared helper.

## What was built

**Sequence 1, best-effort parity (T1, T2).**
- `StorageCleanup` in `api-usecases` exposes `internal fun runQuietly(what, block)` (runCatching +
  WARN log) and three top-level extensions: `ImageStore.deleteQuietly`,
  `RenditionCache.evictImageQuietly`, `ExportArchiveStore.deleteQuietly`. The first logger in
  `api-usecases` main source.
- Every side-effect storage cleanup call site is now best-effort. The T2 task review widened the
  scope beyond the supersede paths to four rollback / no-op-swap / post-commit sites that were
  masking the original error (`SetPinImage`, `DownloadPinImage` rollback and no-op-swap,
  `UserDataExportRequester` post-commit supersede). `UserDataExportDeleter` is the one deliberate
  exception: its delete IS the user's `DELETE /me/exports/{id}`, and a disk failure must surface;
  it carries an inline comment documenting the D1 exception.

**Sequence 2, periodic GC (T3 to T10).**
- Four `Reap*` use cases, each `fun reap(): Int`:
  - `ReapExpiredSessionTokens` (`@ApplicationScoped`).
  - `ReapOrphanedStorage` (batched; drives `forEach*OnDisk`, chunks by `gc.orphan_batch_size`,
    queries `findMissing*` per chunk, evicts/deletes via the Quietly extensions; skips storage keys
    that do not parse to an export id).
  - `ReapTombstonedAccounts` (re-drives `AccountDeletionCleaner.deleteAccountData` per tombstone,
    with item-level try/catch so one bad tombstone cannot block the rest; the second logger in
    `api-usecases`).
  - `ReapTerminalTasks`.
- New port methods: `RenditionCache.forEachImageIdOnDisk`, `ExportArchiveStore.forEachStorageKeyOnDisk`
  (loan-pattern, lazy sequence, absent-directory tolerant), `ImageRepositoryInterface.findMissingImageIds`,
  `UserDataExportRepositoryInterface.findMissingExportIds`, `SessionTokenRepositoryInterface.deleteExpiredBefore`,
  `UserRepositoryInterface.findTombstonedUsersModifiedBefore`, `TaskQueueInterface.deleteTerminalBefore`.
- `GcConfig` (`@ConfigMapping(prefix = "gc")`), a `GC_SCHEDULER` single-thread producer in
  `TaskRuntimeProducers`, `GcLifecycle` (mirrors `ExportRetentionLifecycle`: startup sweep then
  `scheduleWithFixedDelay`, `safeAll` runs each sweep in its own try/catch so one throw does not stop
  the others), and `GcProducers` in `api-application/wiring` for the three use cases that take a
  primitive.
- Migration `1.12` (additive): `ix_session_tokens_expires_at` and a composite
  `ix_tasks_state_when_modified`, so the cutoff sweeps are targeted, not O(n) full scans.

## Pitfalls / friction

- **`api-usecases` test SLF4J binding.** `StorageCleanup` is the module's first logger; its unit
  tests run without a Quarkus-provided backend, so `kotlin-logging-jvm` 8.x (SLF4J by default) threw
  `NoClassDefFoundError`. Resolved with `org.slf4j:slf4j-nop` as `testRuntimeOnly` (chosen over
  `slf4j-simple` to keep test output silent; these tests assert outcomes, not logs).
- **`@WhenModified` test seeding.** `UserRepositoryTest` and `EbeanTaskQueueTest` cannot back-date
  `when_modified` with a plain `save` (Ebean overwrites it on every save); both use
  `database.sqlUpdate("UPDATE ... SET when_modified = ? WHERE id = ?")`. The idiom is in those tests.
- **detekt `MaxLineLength` on test names.** Backstop test names that run past 120 chars trip the
  gate. Two were shortened in T2 and T8. Keep behavioural test names under the limit.
- **`reap()` counts are best-effort.** `ReapOrphanedStorage.reap()` returns "orphans identified for
  reclamation", not "successfully deleted" (the Quietly extensions swallow failures). Documented in
  the KDoc; do not treat the count as a success metric.
- **`forEach*OnDisk` and absent directories.** Both ports treat an absent directory as empty
  (`block(emptySequence())`) rather than throwing, mirroring `discardOrphanedStagedFiles`. A fresh
  install with no activity does not log sweep failures.
- **TDD red-commit amends.** Two implementers amended the test-only commit once (pre-push) to add a
  case that covers an extra branch. Acceptable as long as the amended commit stays tests-only and
  still fails red.
- **`@Suppress("TooGenericExceptionCaught")`.** Used on `safeAll` and `ReapTombstonedAccounts` for
  the intentional broad catches, each with a reason comment, matching the project convention.

## Not validated against real conditions

- The full gate is green on this host (including the `api-application` integration tests, which boot
  Quarkus and resolve the full CDI graph, so `GcProducers` and the scheduler wiring are exercised).
  The multi-arch container image build behind `validate / build-image` is not covered by any local
  command.
- The GC cadence (`P1D` default) and the grace windows (`tombstone_grace=PT24H`,
  `terminal_task_grace=P7D`) are unit-tested for behaviour but not observed under real load or wall
  clock. They are config keys, tunable without redeploy.
- Index effectiveness is preventive, not measured (no production dataset in alpha); a query plan is
  the future confirmation if a sweep ever shows latency.

## Suggested next step

- Integrate: push and open a PR, merge with `gh pr merge --rebase` (squash is disabled on this repo).
- Then run Improve: the failures the gate did not catch are recorded below for that discussion.
- The backlog holds one adjacent item: `imageStore.discard` has the same error-masking shape in the
  same rollback blocks and has no `discardQuietly` counterpart yet; extend `StorageCleanup` when a
  non-delete cleanup needs best-effort.

## Improve input (failures the gate did not catch)

- **detekt was not run per task in Sequence 1.** T1/T2 verified with `test` + `koverVerify` only, so
  a `MaxLineLength` on a test name reached the holistic review instead of the task review. T3 to T10
  added `:module:detekt` to the per-task verification and caught nothing further. Candidate remedy:
  state "per-task verification runs `test` + `koverVerify` + `detekt`" in `agents/project.md`.
- **Em-dashes in the plan reached the holistic review.** The spec and ADR were clean; only the plan
  had them (56, then 2 trailing). The gate has no docs em-dash check. Candidate remedy: a small
  grep-based check, or fold "no em-dash in docs" into the existing pre-commit hook.
- **The plan's T2 scope note was wrong.** "SetPinImage/DownloadPinImage already wrapped in
  runCatching" was true only of the supersede paths; the rollback paths were missed. The task review
  caught it (the workflow worked), but the plan review did not. No structural remedy identified: the
  gap is subtle and the per-task review is the backstop that fired.
