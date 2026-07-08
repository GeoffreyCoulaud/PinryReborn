# Spec: persistent task queue

Date: 2026-07-08
Status: in review
Slug: `task-queue`

## 1. Context and goal

The upcoming image-hosting feature needs to fetch images server-side: when a URL-only pin
is created (ingestion "mode B"), the server must download the referenced image, store it,
and attach it to the pin. That download is slow, network-bound, failure-prone, and must
survive restarts. It should not happen inline on the request thread.

This spec introduces a **persistent, database-backed background task queue** embedded in
the application's own SQLite database, drained by a configurable pool of in-process
workers. It is deliberately **generic**: the same queue will later carry thumbnail
generation and perceptual-hashing tasks. The guiding requirement from the operator is that
it be **rock-solid and easy to evolve**.

### Decomposition

This is **sub-project 1** of the image-hosting effort. It ships the queue as reusable
infrastructure, validated in isolation with test handlers. The concrete `pin.download`
handler, the `Image` domain entity, and the REST upload/serve surface belong to
**sub-project 2** (image hosting) and are **out of scope here**. The queue is designed so
that adding those consumers later is "register a handler", not "re-architect the queue".

### Fixed constraints (from AGENTS.md and the environment)

- **SQLite, WAL mode, single-writer**: many concurrent readers, exactly one write
  transaction at a time; other writers serialize (block on `busy_timeout`, else
  `SQLITE_BUSY`). No `SELECT ... FOR UPDATE SKIP LOCKED`.
- **Single node, single process** (one JVM). No external broker. The queue lives in the
  SQLite database itself.
- **Clean / Hexagonal**: `api-domain` is pure (no I/O). All I/O lives in adapters.
- **Strict TDD, 100% branch coverage per package**, gated in CI and pre-push.

## 2. Build vs buy (decision)

Surveyed the JVM/Kotlin landscape, verifying SQLite support against current docs:

| Library | SQLite | Quarkus | Verdict |
|---|---|---|---|
| db-scheduler (kagkarlsson) | No (relies on `SKIP LOCKED`; no schema) | via CDI | Rejected |
| db-kqueue (jopenlibs) | No (PG/MSSQL/Oracle/H2) | No | Rejected |
| JobRunr | Yes (`SqLiteStorageProvider`) + official Quarkus extension | Yes | Rejected: lambda-serialization paradigm clashes with our `kind`+`payload` hexagonal model; **transactional enqueue is a paid (Pro) feature**; heavy clustering/dashboard machinery for a need we do not have. |
| Honker | Yes (purpose-built) | To integrate | Rejected: native SQLite extension, young single-author project; too risky for a "rock-solid" foundation. |

**Decision: build bespoke.** No mature library satisfies *both* SQLite *and* our
hexagonal/transactional model. The design is small (one table, five states, claim/settle/
reaper behind a port), exploits SQLite's single-writer model instead of emulating
`SKIP LOCKED`, gives us the transactional-outbox guarantee for free (only possible when we
own the table), and fits entirely under our TDD + 100%-branch regime.

## 3. Core insight: single-writer *is* the mutual exclusion

On PostgreSQL a queue needs `SKIP LOCKED` because many workers write concurrently. SQLite
has the opposite property, hence the opposite solution: at most one write transaction
commits at a time. A claim expressed as

```
BEGIN IMMEDIATE;
  SELECT one runnable task;
  UPDATE it to RUNNING (set lease + fencing token, attempts++);
COMMIT;
```

is **already atomic and race-free against every other worker**: serialization is the
mutual exclusion. We do not emulate `SKIP LOCKED`; we keep the claim transaction sub-
millisecond so serialization is a non-issue.

**What "N workers" buys.** Claims serialize through the single writer, but a claim is a
sub-ms write; even 8-16 workers contend negligibly. The parallelism lives entirely in the
**execute** phase (network downloads), which runs **outside any transaction**. N workers =
N concurrent downloads with one-at-a-time bookkeeping. That is the right shape for
I/O-bound work.

## 4. Architecture (hexagonal placement)

*Policy* is pure; *mechanism* is an adapter. Time enters the domain **as a value, not as a
call**: the runtime reads `clock.now()` at the edge and passes `now` into
`claimNext(now, ...)` / `reapExpired(now)`, which the SQL binds as a parameter. This keeps
both policy and lease-expiry comparisons deterministic and injectable (the precondition for
100% branch coverage without flaky timing tests).

| Concern | Module | Purity |
|---|---|---|
| `Task` entity, `TaskState` enum, legal transitions | `api-domain` | Pure |
| `BackoffPolicy.nextAttemptAt(attempt, now)` (retry vs dead decision) | `api-domain` | Pure |
| `Clock` port (returns `Instant`) | `api-domain` | Pure interface |
| `TaskQueue` port: `enqueue`, `claimNext`, `succeed`, `retryLater`, `markDead`, `cancel`, `reapExpired` | `api-domain` | Pure interface |
| `TaskHandler` interface + `TaskHandlerRegistry` (dispatch by `kind`) | `api-usecases` | Pure orchestration |
| `TaskProcessor` use-case (claim -> dispatch -> settle) | `api-usecases` | Pure orchestration |
| `EnqueueTask`, `CancelTask`, `ReapExpiredTasks` use-cases | `api-usecases` | Pure orchestration |
| Ebean `TaskEntity`, `EbeanTaskQueue` (typed query beans + `UpdateQuery`, IMMEDIATE txn), SQLite pragmas, migration | `api-persistence-sqlite` | I/O |
| Worker pool, poller (`@Scheduled`), lifecycle hooks, system `Clock` impl, config | `api-presentation-quarkus` | I/O |

The worker runtime is a **driving adapter** (a scheduler-triggered poller that invokes the
`TaskProcessor` use-case on a timer), the same layer role as the REST controllers. It is
placed in `api-presentation-quarkus` (the Quarkus inbound-adapter module, in the coverage
gate) under a dedicated `tasks` package, kept separate from the HTTP controllers. Extracting
a dedicated `api-task-runtime` module later is a clean, non-breaking refactor if the surface
grows; see open points (§16).

## 5. Task lifecycle and states

```
                enqueue
                   |
                   v
        +------> PENDING <--------------- retryLater (available_at = now + backoff)
        |          | claim (lease)
        |          v
        |       RUNNING --- succeed --------> SUCCEEDED (terminal)
   lease|expired  | |  |
   (reaper)       | |  +-- fail, retryable & attempts<max --> (back to PENDING)
        +---------+ |
                    +---- fail, non-retryable OR attempts>=max --> DEAD (terminal)

     any non-terminal --- cancel --> CANCELLED (terminal)
```

| State | Meaning | Terminal |
|---|---|:--:|
| `PENDING` | Runnable at/after `available_at`. Covers new, delayed, and backoff-waiting: one state, different `available_at`. | No |
| `RUNNING` | Claimed, lease held, executing. | No |
| `SUCCEEDED` | Completed OK. | Yes |
| `DEAD` | Exhausted retries or non-retryable failure (the dead-letter state). Row **kept** with `last_error` and `attempts` for diagnosis and manual replay. | Yes |
| `CANCELLED` | Cancelled before completion (see §9). | Yes |

Rationale: collapsing new / delayed / retry-waiting into `PENDING` governed by
`available_at` is the single most valuable modeling decision (fewer states, fewer branches;
scheduling and backoff become the same mechanism). `DEAD` is a first-class state, not a
delete: deleting a permanently-failed task destroys the forensic evidence you most need.
`RUNNING` is a *leased* state, never trusted: an expired lease means runnable-again, so
there is no separate "stuck" state (expiry is computed, not stored). Legal transitions are a
small closed set, enumerated as pure functions in the domain; each is a test case.

## 6. Storage schema

A **single table**, polymorphic by `kind` + serialized `payload`. One table keeps the claim
query simple and its index hot.

| Column | Type | Purpose |
|---|---|---|
| `id` | INTEGER PK (rowid) | Identity and FIFO tie-breaker |
| `kind` | TEXT | Task-type discriminator (`"pin.download"`, ...). Drives handler dispatch. New kinds add zero DDL. |
| `payload` | TEXT (JSON) | Opaque, kind-specific input. The domain treats it as an opaque string; each handler owns its own (de)serialization. |
| `state` | TEXT | `PENDING` / `RUNNING` / `SUCCEEDED` / `DEAD` / `CANCELLED` |
| `priority` | INTEGER | Higher runs first (default 0) |
| `available_at` | INTEGER (epoch ms) | Not runnable before this. Powers delay, scheduling, and backoff uniformly. |
| `attempts` | INTEGER | Incremented on each claim |
| `max_attempts` | INTEGER | Per-task retry budget (defaults to per-kind config) |
| `lease_expires_at` | INTEGER (epoch ms), nullable | When the current `RUNNING` lease dies -> eligible for reaping |
| `lease_id` | TEXT, nullable | **Fencing token**: identifies the current owner; every settle is conditional on it |
| `version` | INTEGER (Ebean `@Version`) | Optimistic-lock back-stop for fencing |
| `cancel_requested` | INTEGER (bool), default 0 | Cancellation requested for an in-flight task (see §9) |
| `dedup_key` | TEXT, nullable | Optional idempotency key; partial-unique among live tasks |
| `last_error` | TEXT, nullable | Truncated diagnostic from the last failure |
| `created_at` / `updated_at` | INTEGER (epoch ms) | Age / latency metrics, audit |

Indexes (this is where SQLite-as-a-queue lives or dies):

- **Claim index** (partial, ordered): `(priority DESC, available_at ASC, id ASC) WHERE state = 'PENDING'`.
  The partial predicate keeps the index tiny (only runnable rows) and makes the claim
  `SELECT ... LIMIT 1` an index-only seek. A status-less scan over a growing table is the #1
  SQLite-queue performance killer; this index is the antidote.
- **Reaper index** (partial): `(lease_expires_at) WHERE state = 'RUNNING'`, so expiry reaping is a seek.
- **Dedup index** (partial-unique): `(dedup_key) WHERE dedup_key IS NOT NULL AND state IN ('PENDING','RUNNING')`.
  Unique only among live tasks: a fresh run after a previous one completed is allowed.

Adding a task type is: register a `TaskHandler` for a new `kind` string and define its
payload DTO. **No migration, no new table.** This is the crux of evolvability.

## 7. Claim / execute / settle protocol

Three phases; correctness is entirely about *which phase holds the write lock*.

| Phase | Transaction | Duration | Content |
|---|---|---|---|
| **Claim** | Short `BEGIN IMMEDIATE` write txn | sub-ms | Select one runnable task, flip to `RUNNING`, set lease + fencing token, `attempts++`, commit |
| **Execute** | **None** | seconds-minutes | Run the handler (download, file I/O). Zero DB locks held. |
| **Settle** | Short `BEGIN IMMEDIATE` write txn | sub-ms | Conditional on fencing token: `SUCCEEDED`, or reschedule `PENDING` with backoff, or `DEAD`, or `CANCELLED` |

The claim below is the *effective* SQL; it is implemented with **Ebean's typed query beans
and `UpdateQuery`, not hand-written SQL** (see "Ebean mapping" after the block), inside one
IMMEDIATE transaction:

```sql
SELECT id FROM task
 WHERE state = 'PENDING' AND available_at <= :now
 ORDER BY priority DESC, available_at ASC, id ASC
 LIMIT 1;                        -- uses the partial claim index

UPDATE task
   SET state='RUNNING', lease_id=:leaseId, lease_expires_at=:now + :leaseMs,
       attempts=attempts+1, version=version+1, updated_at=:now
 WHERE id=:id;                   -- safe: we hold the single write lock
```

Because the whole IMMEDIATE transaction holds the write lock, no other worker can interleave
between the select and the update: the guarantee that Postgres needs `SKIP LOCKED` for is
free here.

**Ebean mapping (no raw SQL).** Verified against the Ebean 19.2.0 sources (the version in
`libs.versions.toml`):

- **Claim**: `QTask().state.eq(PENDING).availableAt.le(now).orderBy()...setMaxRows(1).findOneOrEmpty()`,
  then mutate the fetched bean (`state`, `leaseId`, `leaseExpiresAt`, `attempts++`) and
  `db.save(it)` (Ebean bumps `@Version` automatically). The select already returns `kind` +
  `payload` for dispatch. Safe because the IMMEDIATE transaction serializes writers.
- **Settle (fenced)**: `QTask().id.eq(id).leaseId.eq(leaseId).asUpdate().set(...).update()`.
  `UpdateQuery.update()` returns the **number of rows affected**; `0` means "I was fenced"
  (discard the result, no exception). This is the fencing check, in the typed API.
- **Reaper**: `QTask().state.eq(RUNNING).leaseExpiresAt.le(now).asUpdate().set("state", PENDING)...update()`.
- **Increment** (`attempts = attempts + 1`) on the set-based path uses `UpdateQuery.setRaw("attempts = attempts + 1")`
  (a typed-query property expression, not free-form SQL). The fetch-mutate-save claim path
  does not even need this (`bean.attempts++` in Kotlin).
- Raw `SqlUpdate` is reserved as a **last-resort escape hatch** only if a specific construct
  cannot be expressed in the typed API; none is anticipated.

Critical mechanics:

- **Force IMMEDIATE begin, not the default deferred begin.** With deferred begin, two workers
  can each take a read lock then both try to upgrade to write -> unresolvable upgrade deadlock
  that `busy_timeout` cannot save. IMMEDIATE takes the write lock at begin, so the second
  worker cleanly waits. This is a **datasource / connection-level** setting
  (sqlite-jdbc `transaction_mode=IMMEDIATE`), independent of the typed-vs-raw choice; the
  single most important connection-level setting. `Transaction.connection()` is available as a
  per-connection escape hatch if a pragma must be set that way.
- **Keep write-lock hold time minimal**: claim/settle do only the state flip. JSON
  encode/decode and any computation happen before / after the transaction, never inside.
- **Never span a write transaction across the download** (the cardinal sin, §15).

## 8. Crash recovery: lease + reaper + fencing

At-least-once is the only honest guarantee; make it safe.

- **Lease / visibility timeout.** Claim stamps `lease_expires_at = now + leaseDuration` and
  a unique `lease_id`.
- **Reaper.** A `@Scheduled` sweep (every ~lease/2) flips expired-lease `RUNNING` rows back
  to `PENDING` and records "reclaimed after lease expiry" in `last_error`. Kept separate
  from the hot claim query for a clean, observable recovery event. A one-time reaper sweep
  also runs at boot to reclaim tasks orphaned by the previous process's crash.
- **Fencing tokens (the subtle correctness point).** Scenario: task leased to worker A ->
  A stalls (GC pause) -> lease expires -> reaper re-queues -> worker B claims and runs it ->
  A wakes and tries to write its stale result. Prevented by making every settle conditional
  on the fencing token: `UPDATE ... WHERE id=:id AND lease_id=:myLeaseId`. Zero rows updated
  means "I was fenced": discard the result, log, do not retry the write. Ebean's `@Version`
  is the same idea as a back-stop.
- **Consumers must be idempotent.** With lease expiry + fencing, a task can still *execute*
  more than once; at-least-once means side effects can repeat. Exactly-once *effect* is only
  achievable by idempotent handlers (download to a temp file, then atomic rename keyed by
  task id / content hash). This is a contract on each handler, not a queue feature.
- **Heartbeat: deferred for v1.** The lease is set comfortably above the maximum task
  duration (download timeout well under lease), so v1 does not need in-flight lease
  extension. The lease/fencing model already leaves room to add heartbeat later.

## 9. Cancellation (v1 scope)

- **Cancel a `PENDING` task**: atomic flip to `CANCELLED`, guarded by `WHERE state='PENDING'`.
  Simple and complete.
- **Cancel a `RUNNING` task**: set `cancel_requested = 1`. When the current attempt settles,
  the task transitions to `CANCELLED` instead of being rescheduled or marked succeeded/dead.
  The in-flight execution is **not interrupted** (best-effort); its result is discarded via
  the fencing check. This stops any further retries.
- **Cooperative mid-execution interruption** (handler polls `cancel_requested` and aborts
  early) is **deferred**. The `cancel_requested` column leaves room for it.
- Cancellation is exposed as the `CancelTask` use-case (programmatic, e.g. cancel a pin's
  pending download when the pin is hard-deleted). A REST admin surface for tasks is out of
  scope for v1.

## 10. Failure handling and retries

| Aspect | Decision | Rationale |
|---|---|---|
| Retryable vs not | The handler classifies its failure (transient: timeout / HTTP 5xx -> retry; permanent: 404 / validation -> straight to `DEAD`) | Retrying a deterministic failure just burns the budget |
| Backoff | Exponential with **full jitter**: `delay = random(0, min(cap, base * 2^attempt))`, a pure function of `attempt` + an **injected seeded RNG** | Exponential spares a struggling dependency; jitter prevents a thundering herd of synchronized retries; seeded RNG keeps it deterministic in tests |
| Attempt limit | Per-kind default `max_attempts`, per-task override | Different work tolerates different retry counts |
| Exhaustion | Move to `DEAD` with `last_error` + `attempts`; keep the row | Dead-letter for diagnosis and manual replay |
| Reschedule | `state=PENDING`, `available_at = now + backoff(attempt)` | Backoff and delay are the same mechanism |

Manual replay of a `DEAD` task = reset to `PENDING`, `attempts=0`, `available_at=now`. A
supported operator action (programmatic in v1).

## 11. Transactional enqueue and dedup

The killer advantage of embedding the queue in the app's own SQLite database: enqueue a
task **inside the same Ebean transaction** as the domain change that triggers it.

```
BEGIN IMMEDIATE
  <domain write: create Pin>
  <insert task row 'pin.download'>     -- same txn
COMMIT
```

Either both commit or neither does. You can **never** end up with a pin but no download
task, nor a task referencing a rolled-back pin: the transactional-outbox guarantee, for
free, with no outbox relay and no dual-write, precisely because there is one database. The
`TaskQueue.enqueue` port accepts an optional existing transaction so callers can join the
domain write. (Consumed in sub-project 2; validated here with a test that rolls back and
asserts neither row exists.)

**Deduplication** via the `dedup_key` partial-unique index: a second live enqueue with the
same key is coalesced/rejected. Optional (nullable): most tasks do not need it. Dedup stops
duplicate *enqueues*; idempotent handlers (§8) stop duplicate *executions* causing duplicate
*effects*.

## 12. Worker runtime (Quarkus)

Verified against current Quarkus docs (context7):

- **Worker pool**: a bounded `ManagedExecutor` (or a bounded `ExecutorService`) whose thread
  count is the configurable "N workers". Bounded pool + bounded queue = natural backpressure.
- **Do not run the download on the scheduler thread.** A `@Scheduled` poller dispatches work
  onto the worker executor; the blocking download runs there, never on the scheduler/Vert.x
  thread.
- **Work discovery: simple polling in v1.** A baseline `@Scheduled(every="Ns", concurrentExecution=SKIP)`
  poller claims and dispatches available tasks; `SKIP` (built-in) prevents overlapping ticks.
  An in-process wakeup (a `Semaphore` nudged by `enqueue` for near-zero latency) is
  **deferred**: it is a pure latency optimization and adds shared in-JVM state that
  complicates the 100%-branch story. The poll interval trades latency vs idle DB load.
- **Lifecycle.** `@Observes StartupEvent` spins up the pool + poller and runs the boot
  reaper sweep. `@Observes ShutdownEvent` drains: stop claiming new tasks, let in-flight
  tasks finish, `shutdown()` + bounded `awaitTermination`; tasks still running at the
  deadline are left `RUNNING` and reclaimed on next boot via lease expiry. Bound the wait
  with `quarkus.shutdown.timeout`. Quarkus's built-in graceful shutdown covers HTTP, not our
  pool, so we own the worker drain explicitly.

## 13. SQLite configuration

Applied at the datasource / connection level (`EbeanDatabaseProducer` / JDBC URL), confirmed
against sqlite-jdbc docs during implementation:

- `journal_mode = WAL` (concurrent readers alongside the one writer).
- `synchronous = NORMAL` (durable across app/process crash; only a power/OS crash can drop
  the last commits, never corrupt; the standard fast-and-safe queue choice).
- `busy_timeout = 5000` (a waiting writer blocks up to the timeout rather than erroring
  instantly; a real `SQLITE_BUSY` past the timeout is a retryable, logged, tuning signal).
- `transaction_mode = IMMEDIATE` (see §7).
- Periodic WAL checkpoint (auto-checkpoint) so `-wal` does not grow unbounded under sustained
  writes.

## 14. Ordering, priority, observability, configuration

- **Ordering**: strict global FIFO is neither achievable nor promised once N workers run in
  parallel. We provide a total *claim* order (`priority DESC, available_at ASC, id ASC`), not
  a completion order. Priority column present; **anti-starvation aging is deferred** (add
  when starvation is observed).
- **Observability** (v1 minimal): Micrometer gauges/counters over the single table plus
  structured logs on every state transition (`taskId`, `kind`, `attempt`, `outcome`,
  `leaseId`):
  - queue depth (`PENDING AND available_at<=now`), oldest runnable age, in-flight count,
    reclaim rate, transitions to `DEAD` per kind, `SQLITE_BUSY` count.
- **Configuration surface** (`application.properties`): worker count, poll interval, lease
  duration, `busy_timeout`, and per-kind backoff base/cap and `max_attempts` defaults.

## 15. Anti-patterns to avoid (SQLite-as-a-queue)

| Anti-pattern | Consequence | Guard |
|---|---|---|
| Holding a write txn across the download | The single writer blocks; the whole app's writes stall | Claim -> commit -> execute outside any txn -> settle in a new short txn |
| Default (deferred) `BEGIN` for claims | Read-then-upgrade deadlock between two workers | `transaction_mode=IMMEDIATE` |
| No `busy_timeout` | Every write collision throws instantly | `busy_timeout=5000` |
| Status scan without the partial index | Full-table scan on every claim as terminal rows pile up | Partial claim index `WHERE state='PENDING'` |
| Unconditional settle (no fencing) | Zombie worker overwrites a re-claimed task's result | Settle `WHERE id=? AND lease_id=?` (+ `@Version`) |
| Trusting `RUNNING` = alive | Crashed worker's tasks stuck forever | Lease + reaper |
| Tight polling loop | Polling storm, WAL churn | Reasonable poll interval; `concurrentExecution=SKIP` |

## 16. Scope

### In scope

- Generic queue infrastructure across `api-domain` (entity, states, ports, backoff policy,
  clock port), `api-usecases` (handler interface + registry, `TaskProcessor`, `EnqueueTask`,
  `CancelTask`, `ReapExpiredTasks`), `api-persistence-sqlite` (Ebean model, `EbeanTaskQueue`
  using typed query beans + `UpdateQuery` for IMMEDIATE claim/settle/reap, SQLite pragmas,
  migration), and the
  `api-presentation-quarkus` worker runtime (pool, poller, lifecycle, system clock, config).
- The full state machine, claim/execute/settle protocol, lease + reaper + fencing crash
  recovery, exponential-backoff-with-jitter retries, dead-letter, transactional enqueue,
  optional dedup, cancellation (§9 v1 semantics), minimal metrics.
- Full unit tests to 100% branch per new package (see §17).

### Out of scope

- The `pin.download` handler, the `Image` domain entity, and REST upload/serve endpoints
  (sub-project 2, image hosting).
- Thumbnail-generation and perceptual-hash handlers (future salvos; they reuse this queue).
- Heartbeat / in-flight lease extension; in-process enqueue wakeup; priority aging /
  anti-starvation; per-key (partition) serialization; recurring / cron scheduling; per-kind
  rate limits; task chaining / DAGs; multi-node workers; retention / purge of terminal rows;
  a REST admin surface for tasks. Each is an additive layer over the v1 primitives; the
  design leaves column- or seam-shaped holes for them but does not build them.

## 17. Testing strategy

The layering makes 100% branch reachable without timing-dependent tests. Golden rules:
**inject the clock as a value, seed the RNG, drive concurrency with latches/barriers, never
`Thread.sleep`.**

| Concern | Where | Deterministic test |
|---|---|---|
| State machine, legal transitions | domain (pure) | Table-driven over every `(state,event)->state'`; both sides of each branch |
| Backoff / retry / dead decision | domain (pure) | Fixed `now` + fixed RNG seed -> assert exact `available_at`; cover attempt<max and >=max, retryable and not |
| Lease expiry / reaping | persistence | Insert a row with `lease_expires_at` in the past via a controlled `now`; assert reclaim |
| Claim atomicity / no double-claim | persistence | Real SQLite; threads all `claimNext`; assert claimed ids are a duplicate-free set equal to the enqueued set |
| Fencing | persistence | Claim as L1; simulate reap + re-claim as L2; settle with L1 -> assert 0 rows / rejected |
| `SQLITE_BUSY` handling | persistence | Hold a write txn on one connection, claim on another with tiny `busy_timeout` -> assert the retryable path |
| Transactional enqueue | persistence | Enqueue + domain write in one txn, force rollback -> assert neither row exists |
| Cancellation | usecases + persistence | Cancel PENDING (flip); request-cancel RUNNING then settle -> assert `CANCELLED`, no retry |
| Shutdown drain | runtime | Latch-controlled handler + fake executor; fire `ShutdownEvent`; assert "stopped claiming" flag + `awaitTermination`; cover drained-in-time and hit-deadline branches |

## 18. Verification

- `./gradlew check koverVerify` green: detekt + all tests + 100% branch on every new package.
- The concurrency claim test passes reliably (no duplicate claims) on real SQLite in WAL.
- `./gradlew build` green; OpenAPI spec regenerated if any surface changed (none expected in
  v1: no REST endpoints added).
- CI `validate / gate` passes on the PR.
- Not validated against real hardware / a running deployment: no `pin.download` consumer
  exists yet (sub-project 2), so the queue is exercised only by test handlers.

## 19. Delivery outline

Detailed plan follows in `docs/plans/` (writing-plans). High-level staging, each phase red-
first under TDD:

1. **Domain**: `Task`, `TaskState`, transitions, `BackoffPolicy`, `Clock` and `TaskQueue`
   ports. Pure, fully unit-tested.
2. **Persistence**: Ebean `TaskEntity` + migration, `EbeanTaskQueue` (IMMEDIATE claim /
   settle / reap, fencing), SQLite pragmas. Concurrency and fencing tests on real SQLite.
3. **Use-cases**: `TaskHandler` + registry, `TaskProcessor`, `EnqueueTask`, `CancelTask`,
   `ReapExpiredTasks`. MockK-tested with fake handlers.
4. **Runtime**: worker pool, `@Scheduled` poller, lifecycle (startup/boot-reaper/shutdown
   drain), system `Clock`, config, metrics. Tested with latch-controlled fake handlers.

## 20. Risks / open points

- **Runtime module placement**: v1 puts the worker runtime in `api-presentation-quarkus`
  (in-gate, correct adapter role). If it grows, extract a dedicated `api-task-runtime`
  module. Flagged for operator sign-off.
- **Ebean typed API is sufficient (confirmed on the 19.2.0 sources)**: `UpdateQuery` gives
  typed `set`/`setRaw`/`where`/`update()`-with-row-count (the fencing signal), and query
  beans give `setMaxRows(1)`/`findOneOrEmpty()` for the claim. No raw SQL needed. The one item
  still to confirm during implementation is the **IMMEDIATE begin-mode wiring** (sqlite-jdbc
  `transaction_mode=IMMEDIATE` on the `ebean-datasource` / JDBC URL), which is a
  connection-level setting orthogonal to the query API.
- **100% branch on error/concurrency paths**: `SQLITE_BUSY` handling, fencing "0 rows", and
  shutdown deadline branches need deliberate seams to cover both sides without flakiness.
- **`@Version` + fencing interplay**: ensure the optimistic-lock back-stop does not turn a
  legitimate fenced no-op into a thrown `OptimisticLockException` we fail to handle.
