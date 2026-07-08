# Handoff — persistent task queue (sub-project 1 of image hosting)

Date: 2026-07-08
Branch: `feat/task-queue`
Spec: `docs/specs/2026-07-08-task-queue.md` (see §21 Amendments)
Plan: `docs/plans/2026-07-08-task-queue.md`

## Current state

A generic, SQLite-backed, in-process background task queue is **built, fully tested, and
gate-green** (`./gradlew check koverVerify` BUILD SUCCESSFUL: detekt + all tests + 100% branch
per in-gate package). It is exercised end-to-end by a boot integration test (enqueue a
no-handler task, the running runtime claims → executes → settles it to `DEAD`) plus 81
pre-existing integration tests that now boot with the queue runtime active.

**Not yet integrated to `main`** — awaiting the operator's integration choice (PR). No real
task handler exists yet: `pin.download`, the `Image` model, and the REST upload/serve surface
are **sub-project 2 (image hosting)**, out of scope here.

## What was built (by layer)

- **`api-domain`** (pure): `Task`, `TaskState` (PENDING/RUNNING/SUCCEEDED/DEAD/CANCELLED),
  `NewTask`, `ClaimedTask`, `Clock` port, `BackoffPolicy` + `ExponentialBackoffWithJitter`
  (seeded-RNG, guarded exponent), `TaskQueueInterface` port.
- **`api-persistence-sqlite`**: `TaskModel` (`tasks` table) + migration `1.3.sql` with the three
  partial indexes (claim / reaper / dedup); `EbeanTaskQueue` implementing the port with typed
  query beans + `UpdateQuery` (fencing = `update() > 0`, no raw SQL); claim + enqueue-dedup
  wrapped in explicit single transactions; **single-connection datasource** (option A) with
  WAL / synchronous=NORMAL / busy_timeout. A concurrency test drives 8 threads × 200 tasks and
  proves exactly-once claiming with zero exceptions.
- **`api-usecases`**: `TaskHandler` + `TaskHandlerRegistry`, `PermanentTaskException`,
  `TaskProcessor` (execute + fenced settle: success / retry-with-backoff / dead / best-effort
  cancel), `EnqueueTask` / `CancelTask` / `ReapExpiredTasks`.
- **`api-presentation-quarkus`** (runtime adapter, `tasks` package): `SystemClock`,
  `TaskQueueConfig` (`@ConfigMapping`, `@WithDefault`), `TaskRuntimeProducers`, `WorkerExecutor`
  + `BoundedWorkerExecutor`, `TaskDispatcher` (acquire-permit-before-claim poll loop),
  `TaskWorkerLifecycle` (boot + periodic reaper, poll cadence, graceful drain).
- **`api-application`**: `tasks.*` config defaults + the boot integration test.

## Learned pitfalls (carry forward)

- **`transaction_mode=IMMEDIATE` deadlocks Ebean's default multi-connection pool** (every
  transaction grabs the one write lock at BEGIN). We went single-connection (option A) instead;
  claim atomicity comes from an explicit transaction, not IMMEDIATE. See spec §21.
- **Repository tests were silently hitting an on-disk `data.db`**: the override file must be
  `application-test.properties` (avaje-config), not `ebean-test.properties`. Now `:memory:`
  single-connection.
- **`@ConfigMapping(SNAKE_CASE)` needs underscore keys** (`tasks.worker_count`), not hyphens;
  `@WithDefault` masks the mismatch until a key is actually present.
- **`ScheduledExecutorService` producer collides with Quarkus's built-in `@Default` one** —
  qualify with `@Identifier("task-poll-scheduler")` on producer AND injection point.
- **Logging is I/O**: it belongs in presentation, never in a use-case (AGENTS.md hard rule) — a
  log line in `TaskProcessor` was caught in review and removed.
- **Permit-before-claim needs a release-on-throw guard**: reserving a worker permit before
  `claimNext` means a throwing claim must release the permit or the pool drains permanently.
- **The holistic review earned its keep**: it caught the claim-then-drop orphaning bug that
  every per-task review had waved through as "acceptable over-claim". Do not skip it.

## Not validated

- **No real handler** exists; the queue is proven only via the no-handler→DEAD path + unit
  tests. Crash-recovery of a real in-flight download (lease expiry → reap → re-execute, old
  worker fenced) is proven only at the unit level.
- **Single-connection throughput** under high simultaneous REST + worker load is not
  load-tested (no deadlock — holds are sub-ms — but the one connection is a throughput ceiling).
- Not validated against real hardware or a running deployment.

## Suggested next step

1. **Integrate via PR** (branch protection requires the `validate / gate` check; do not local-
   admin-merge past CI). Squash or rebase (linear history). Tag `vX.Y.Z-task-queue` after merge.
2. Then **sub-project 2 (image hosting)**: `Image` domain entity (a `Pin` owns a list of
   semantically-identical `Image` representations, each with `ImageHash` objects), REST
   multipart upload (mode A) + the `pin.download` `TaskHandler` (mode B) using this queue, and
   the serve endpoint. The download handler must be idempotent (download to temp → atomic
   rename) and needs a network timeout well under the 1-minute lease.
3. **Deferred queue follow-ups** (tracked, non-blocking): transactional-outbox enqueue variant
   (needed by sub-project 2's Pin-create + enqueue), Micrometer gauges (§14), shutdown-drain
   `awaitTermination` ordering, audit-timestamp on bulk updates, isolate the app integration-test
   DB, and a couple of thin test additions (claim tiebreaker ordering, permit-release-on-throw is
   now covered).
